/*
 *  Copyright (c) 2017, salesforce.com, inc.
 *  All rights reserved.
 *  Licensed under the BSD 3-Clause license.
 *  For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.rxgrpc;

import com.google.protobuf.Empty;
import com.salesforce.servicelibs.NumberProto;
import com.salesforce.servicelibs.RxNumbersGrpc;
import io.grpc.*;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.observers.TestObserver;
import io.reactivex.subscribers.TestSubscriber;
import org.awaitility.Duration;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SuppressWarnings({"unchecked", "Duplicates"})
public class CancellationPropagationIntegrationTest {
    private static final int NUMBER_OF_STREAM_ELEMENTS = 10000;

    private static Server server;
    private static ManagedChannel channel;
    private static TestService svc = new TestService();

    private static class TestService extends RxNumbersGrpc.NumbersImplBase {
        private AtomicInteger lastNumberProduced = new AtomicInteger(Integer.MIN_VALUE);
        private AtomicBoolean wasCanceled = new AtomicBoolean(false);
        private AtomicBoolean explicitCancel = new AtomicBoolean(false);

        void reset() {
            lastNumberProduced.set(Integer.MIN_VALUE);
            wasCanceled.set(false);
            explicitCancel.set(false);
        }

        int getLastNumberProduced() {
            return lastNumberProduced.get();
        }

        boolean wasCanceled() {
            return wasCanceled.get();
        }

        void setExplicitCancel(boolean explicitCancel) {
            this.explicitCancel.set(explicitCancel);
        }

        @Override
        public Flowable<NumberProto.Number> responsePressure(Single<Empty> request) {
            // Produce a very long sequence
            return Flowable
                    .fromIterable(IntStream.range(0, NUMBER_OF_STREAM_ELEMENTS)::iterator)
                    .delay(10, TimeUnit.MILLISECONDS)
                    .doOnNext(i -> lastNumberProduced.set(i))
                    .map(CancellationPropagationIntegrationTest::protoNum)
                    .doOnCancel(() -> {
                        wasCanceled.set(true);
                        System.out.println("Server canceled");
                    });
        }

        @Override
        public Single<NumberProto.Number> requestPressure(Flowable<NumberProto.Number> request) {
            if (explicitCancel.get()) {
                // Process a very long sequence
                Disposable subscription = request.subscribe(n -> System.out.println("S: " + n.getNumber(0)));
                return Single
                        .just(protoNum(-1))
                        .delay(250, TimeUnit.MILLISECONDS)
                        // Explicitly cancel by disposing the subscription
                        .doOnSuccess(x -> subscription.dispose());
            } else {
                // Process some of a very long sequence and cancel implicitly with a take(10)
                return request.map(req -> req.getNumber(0))
                        .doOnNext(System.out::println)
                        .take(10)
                        .last(-1)
                        .map(CancellationPropagationIntegrationTest::protoNum);
            }
        }

        @Override
        public Flowable<NumberProto.Number> twoWayPressure(Flowable<NumberProto.Number> request) {
            return requestPressure(request).toFlowable();
        }
    }

    @BeforeClass
    public static void setupServer() throws Exception {
        server = ServerBuilder.forPort(0).addService(svc).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
    }

    @Before
    public void resetServerStats() {
        svc.reset();
    }

    @AfterClass
    public static void stopServer() throws InterruptedException {
        channel.shutdown();
        server.shutdown();
        server.awaitTermination();

        server = null;
        channel = null;
    }

    @Test
    public void clientCanCancelServerStreamExplicitly() throws InterruptedException {
        RxNumbersGrpc.RxNumbersStub stub = RxNumbersGrpc.newRxStub(channel);
        TestSubscriber<NumberProto.Number> subscription = Single.just(Empty.getDefaultInstance())
                .as(stub::responsePressure)
                .doOnNext(number -> System.out.println(number.getNumber(0)))
                .doOnError(throwable -> System.out.println(throwable.getMessage()))
                .doOnComplete(() -> System.out.println("Completed"))
                .doOnCancel(() -> System.out.println("Client canceled"))
                .test();

        Thread.sleep(250);
        subscription.dispose();
        Thread.sleep(250);

        subscription.awaitTerminalEvent(3, TimeUnit.SECONDS);
        // Cancellation may or may not deliver the last generated message due to delays in the gRPC processing thread
        assertThat(Math.abs(subscription.valueCount() - svc.getLastNumberProduced())).isLessThanOrEqualTo(3);
        assertThat(svc.wasCanceled()).isTrue();
    }

    @Test
    public void clientCanCancelServerStreamImplicitly() throws InterruptedException {
        RxNumbersGrpc.RxNumbersStub stub = RxNumbersGrpc.newRxStub(channel);
        TestSubscriber<NumberProto.Number> subscription =  Single.just(Empty.getDefaultInstance())
                .as(stub::responsePressure)
                .doOnNext(number -> System.out.println(number.getNumber(0)))
                .doOnError(throwable -> System.out.println(throwable.getMessage()))
                .doOnComplete(() -> System.out.println("Completed"))
                .doOnCancel(() -> System.out.println("Client canceled"))
                .take(10)
                .test();

        // Consume some work
        Thread.sleep(TimeUnit.SECONDS.toMillis(1));
        subscription.dispose();

        subscription.awaitTerminalEvent(3, TimeUnit.SECONDS);
        subscription.assertValueCount(10);
        subscription.assertTerminated();
        assertThat(svc.wasCanceled()).isTrue();
    }

    @Test
    public void serverCanCancelClientStreamImplicitly() {
        RxNumbersGrpc.RxNumbersStub stub = RxNumbersGrpc.newRxStub(channel);

        svc.setExplicitCancel(false);

        AtomicBoolean requestWasCanceled = new AtomicBoolean(false);
        AtomicBoolean requestDidProduce = new AtomicBoolean(false);

        Flowable<NumberProto.Number> request = Flowable
                .fromIterable(IntStream.range(0, NUMBER_OF_STREAM_ELEMENTS)::iterator)
                .delay(10, TimeUnit.MILLISECONDS)
                .map(CancellationPropagationIntegrationTest::protoNum)
                .doOnNext(x -> {
                    requestDidProduce.set(true);
                    System.out.println("Produced: " + x.getNumber(0));
                })
                .doOnCancel(() -> {
                    requestWasCanceled.set(true);
                    System.out.println("Client canceled");
                });

        TestObserver<NumberProto.Number> observer = request
                .as(stub::requestPressure)
                .doOnSuccess(number -> System.out.println(number.getNumber(0)))
                .doOnError(throwable -> System.out.println(throwable.getMessage()))
                .test();

        observer.awaitTerminalEvent(3, TimeUnit.SECONDS);
        observer.assertComplete();
        observer.assertTerminated();

        await().atMost(Duration.FIVE_HUNDRED_MILLISECONDS).untilTrue(requestWasCanceled);

        assertThat(requestWasCanceled.get()).isTrue();
        assertThat(requestDidProduce.get()).isTrue();
    }

    @Test
    public void serverCanCancelClientStreamExplicitly() {
        RxNumbersGrpc.RxNumbersStub stub = RxNumbersGrpc.newRxStub(channel);

        svc.setExplicitCancel(true);

        AtomicBoolean requestWasCanceled = new AtomicBoolean(false);
        AtomicBoolean requestDidProduce = new AtomicBoolean(false);

        Flowable<NumberProto.Number> request = Flowable
                .fromIterable(IntStream.range(0, NUMBER_OF_STREAM_ELEMENTS)::iterator)
                .delay(10, TimeUnit.MILLISECONDS)
                .map(CancellationPropagationIntegrationTest::protoNum)
                .doOnNext(n -> {
                    requestDidProduce.set(true);
                    System.out.println("P: " + n.getNumber(0));
                })
                .doOnCancel(() -> {
                    requestWasCanceled.set(true);
                    System.out.println("Client canceled");
                });

        TestObserver<NumberProto.Number> observer = request
                .as(stub::requestPressure)
                .doOnSuccess(number -> System.out.println(number.getNumber(0)))
                .doOnError(throwable -> System.out.println(throwable.getMessage()))
                .test();

        observer.awaitTerminalEvent();
        observer.assertComplete();
        observer.assertTerminated();

        await().atMost(Duration.FIVE_HUNDRED_MILLISECONDS).untilTrue(requestWasCanceled);

        assertThat(requestWasCanceled.get()).isTrue();
        assertThat(requestDidProduce.get()).isTrue();
    }

    @Test
    public void serverCanCancelClientStreamImplicitlyBidi() {
        RxNumbersGrpc.RxNumbersStub stub = RxNumbersGrpc.newRxStub(channel);

        svc.setExplicitCancel(false);

        AtomicBoolean requestWasCanceled = new AtomicBoolean(false);
        AtomicBoolean requestDidProduce = new AtomicBoolean(false);

        Flowable<NumberProto.Number> request = Flowable
                .fromIterable(IntStream.range(0, NUMBER_OF_STREAM_ELEMENTS)::iterator)
                .delay(10, TimeUnit.MILLISECONDS)
                .map(CancellationPropagationIntegrationTest::protoNum)
                .doOnNext(x -> {
                    requestDidProduce.set(true);
                    System.out.println("Produced: " + x.getNumber(0));
                })
                .doOnCancel(() -> {
                    requestWasCanceled.set(true);
                    System.out.println("Client canceled");
                });

        TestSubscriber<NumberProto.Number> observer = request
                .compose(stub::twoWayPressure)
                .doOnNext(number -> System.out.println(number.getNumber(0)))
                .doOnError(throwable -> System.out.println(throwable.getMessage()))
                .test();

        observer.awaitTerminalEvent(3, TimeUnit.SECONDS);
        observer.assertTerminated();
        assertThat(requestWasCanceled.get()).isTrue();
        assertThat(requestDidProduce.get()).isTrue();
    }

    @Test
    public void serverCanCancelClientStreamExplicitlyBidi() {
        RxNumbersGrpc.RxNumbersStub stub = RxNumbersGrpc.newRxStub(channel);

        svc.setExplicitCancel(true);

        AtomicBoolean requestWasCanceled = new AtomicBoolean(false);
        AtomicBoolean requestDidProduce = new AtomicBoolean(false);

        Flowable<NumberProto.Number> request = Flowable
                .fromIterable(IntStream.range(0, NUMBER_OF_STREAM_ELEMENTS)::iterator)
                .delay(10, TimeUnit.MILLISECONDS)
                .map(CancellationPropagationIntegrationTest::protoNum)
                .doOnNext(n -> {
                    requestDidProduce.set(true);
                    System.out.println("P: " + n.getNumber(0));
                })
                .doOnCancel(() -> {
                    requestWasCanceled.set(true);
                    System.out.println("Client canceled");
                });

        TestSubscriber<NumberProto.Number> observer = request
                .compose(stub::twoWayPressure)
                .doOnNext(number -> System.out.println(number.getNumber(0)))
                .doOnError(throwable -> System.out.println(throwable.getMessage()))
                .test();

        observer.awaitTerminalEvent();
        observer.assertTerminated();
        assertThat(requestWasCanceled.get()).isTrue();
        assertThat(requestDidProduce.get()).isTrue();
    }

    private static NumberProto.Number protoNum(int i) {
        Integer[] ints = {i};
        return NumberProto.Number.newBuilder().addAllNumber(Arrays.asList(ints)).build();
    }
}
