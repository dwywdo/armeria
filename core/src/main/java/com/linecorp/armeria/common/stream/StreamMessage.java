/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.RequestContext;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

/**
 * A variant of <a href="http://www.reactive-streams.org/">Reactive Streams</a> {@link Publisher}, which allows
 * only one {@link Subscriber}. Unlike a usual {@link Publisher}, a {@link StreamMessage} can stream itself
 * only once. It has the following additional operations on top of what the Reactive Streams API provides:
 * <ul>
 *   <li>{@link #isOpen()}</li>
 *   <li>{@link #isEmpty()}</li>
 *   <li>{@link #whenComplete()}</li>
 *   <li>{@link #abort()}</li>
 * </ul>
 *
 * <h2>When is a {@link StreamMessage} fully consumed?</h2>
 *
 * <p>A {@link StreamMessage} is <em>complete</em> (or 'fully consumed') when:
 * <ul>
 *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
 *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
 *   <li>the {@link Subscription} has been cancelled or</li>
 *   <li>{@link #abort()} has been requested.</li>
 * </ul>
 *
 * <p>When fully consumed, the {@link CompletableFuture} returned by {@link StreamMessage#whenComplete()}
 * will complete, which you may find useful because {@link Subscriber} does not notify you when a stream is
 * {@linkplain Subscription#cancel() cancelled}.
 *
 * <h2>Publication and Consumption of pooled {@link HttpData} objects</h2>
 *
 * <p>{@link StreamMessage} will discard the publication request of a pooled {@link HttpData} silently and
 * release it automatically when the publication is attempted after the stream is closed.
 *
 * <p>For pooled {@link HttpData}, {@link StreamMessage} will convert them into its unpooled version that
 * never leak, so that the {@link Subscriber} does not need to worry about leaks.
 *
 * <p>If a {@link Subscriber} does not want a {@link StreamMessage} to make a copy of a pooled {@link HttpData},
 * specify {@link SubscriptionOption#WITH_POOLED_OBJECTS} when you subscribe. Note that the {@link Subscriber}
 * is responsible for releasing the objects given with {@link Subscriber#onNext(Object)}.
 *
 * <p>{@link Subscriber#onError(Throwable)} is invoked when any exception is raised except the
 * {@link CancelledSubscriptionException} which is caused by {@link Subscription#cancel()}. If you want your
 * {@link Subscriber} get notified by {@link Subscriber#onError(Throwable)} when {@link Subscription#cancel()}
 * is called, specify {@link SubscriptionOption#NOTIFY_CANCELLATION} when you subscribe.
 *
 * @param <T> the type of element signaled
 */
public interface StreamMessage<T> extends Publisher<T> {
    /**
     * Creates a new {@link StreamMessage} that will publish no objects, just a close event.
     */
    static <T> StreamMessage<T> of() {
        return new EmptyFixedStreamMessage<>();
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the single {@code obj}.
     */
    static <T> StreamMessage<T> of(T obj) {
        requireNonNull(obj, "obj");
        return new OneElementFixedStreamMessage<>(obj);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the two {@code obj1} and {@code obj2}.
     */
    static <T> StreamMessage<T> of(T obj1, T obj2) {
        requireNonNull(obj1, "obj1");
        requireNonNull(obj2, "obj2");
        return new TwoElementFixedStreamMessage<>(obj1, obj2);
    }

    /**
     * Creates a new {@link StreamMessage} that will publish the given {@code objs}.
     */
    @SafeVarargs
    static <T> StreamMessage<T> of(T... objs) {
        requireNonNull(objs, "objs");
        switch (objs.length) {
            case 0:
                return of();
            case 1:
                return of(objs[0]);
            case 2:
                return of(objs[0], objs[1]);
            default:
                for (int i = 0; i < objs.length; i++) {
                    if (objs[i] == null) {
                        throw new NullPointerException("objs[" + i + "] is null");
                    }
                }
                return new RegularFixedStreamMessage<>(objs);
        }
    }

    /**
     * Creates a new {@link StreamMessage} from the specified {@link Publisher}.
     */
    static <T> StreamMessage<T> of(Publisher<? extends T> publisher) {
        requireNonNull(publisher, "publisher");

        if (publisher instanceof StreamMessage) {
            @SuppressWarnings("unchecked")
            final StreamMessage<T> cast = (StreamMessage<T>) publisher;
            return cast;
        } else {
            return new PublisherBasedStreamMessage<>(publisher);
        }
    }

    /**
     * Returns a concatenated {@link StreamMessage} which relays items of the specified array of
     * {@link Publisher}s in order, non-overlappingly, one after the other finishes.
     */
    @SafeVarargs
    static <T> StreamMessage<T> concat(Publisher<? extends T>... publishers) {
        requireNonNull(publishers, "publishers");
        return concat(ImmutableList.copyOf(publishers));
    }

    /**
     * Returns a concatenated {@link StreamMessage} which relays items of the specified {@link Publisher}s
     * in order, non-overlappingly, one after the other finishes.
     */
    static <T> StreamMessage<T> concat(Iterable<? extends Publisher<? extends T>> publishers) {
        requireNonNull(publishers, "publishers");

        if (Iterables.isEmpty(publishers)) {
            return of();
        }
        final List<StreamMessage<? extends T>> streamMessages = ImmutableList.copyOf(publishers)
                                                                             .stream()
                                                                             .map(StreamMessage::of)
                                                                             .collect(toImmutableList());
        return new ConcatArrayStreamMessage<>(streamMessages);
    }

    /**
     * Returns a concatenated {@link StreamMessage} which relays items of the specified {@link Publisher} of
     * {@link Publisher}s in order, non-overlappingly, one after the other finishes.
     */
    static <T> StreamMessage<T> concat(Publisher<? extends Publisher<? extends T>> publishers) {
        requireNonNull(publishers, "publishers");
        return new ConcatPublisherStreamMessage<>(of(publishers));
    }

    /**
     * Returns {@code true} if this stream is not closed yet. Note that a stream may not be
     * {@linkplain #whenComplete() complete} even if it's closed; a stream is complete when it's fully
     * consumed by a {@link Subscriber}.
     */
    boolean isOpen();

    /**
     * Returns {@code true} if this stream has been closed and did not publish any elements.
     * Note that this method will not return {@code true} when the stream is open even if it has not
     * published anything so far, because it may publish something later.
     */
    boolean isEmpty();

    /**
     * Returns the current demand of this stream.
     */
    long demand();

    /**
     * Returns {@code true} if this stream is complete, either successfully or exceptionally,
     * including cancellation and abortion.
     *
     * <p>A {@link StreamMessage} is <em>complete</em> (or 'fully consumed') when:
     * <ul>
     *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
     *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
     *   <li>the {@link Subscription} has been cancelled or</li>
     *   <li>{@link #abort()} has been requested.</li>
     * </ul>
     */
    default boolean isComplete() {
        return whenComplete().isDone();
    }

    /**
     * Returns a {@link CompletableFuture} that completes when this stream is complete,
     * either successfully or exceptionally, including cancellation and abortion.
     *
     * <p>A {@link StreamMessage} is <em>complete</em>
     * (or 'fully consumed') when:
     * <ul>
     *   <li>the {@link Subscriber} consumes all elements and {@link Subscriber#onComplete()} is invoked,</li>
     *   <li>an error occurred and {@link Subscriber#onError(Throwable)} is invoked,</li>
     *   <li>the {@link Subscription} has been cancelled or</li>
     *   <li>{@link #abort()} has been requested.</li>
     * </ul>
     */
    CompletableFuture<Void> whenComplete();

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     */
    @Override
    default void subscribe(Subscriber<? super T> subscriber) {
        subscribe(subscriber, defaultSubscriberExecutor());
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param options {@link SubscriptionOption}s to subscribe with
     */
    default void subscribe(Subscriber<? super T> subscriber, SubscriptionOption... options) {
        subscribe(subscriber, defaultSubscriberExecutor(), options);
    }

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to subscribe
     */
    void subscribe(Subscriber<? super T> subscriber, EventExecutor executor);

    /**
     * Requests to start streaming data to the specified {@link Subscriber}. If there is a problem subscribing,
     * {@link Subscriber#onError(Throwable)} will be invoked with one of the following exceptions:
     * <ul>
     *   <li>{@link IllegalStateException} if other {@link Subscriber} subscribed to this stream already.</li>
     *   <li>{@link AbortedStreamException} if this stream has been {@linkplain #abort() aborted}.</li>
     *   <li>{@link CancelledSubscriptionException} if this stream has been
     *       {@linkplain Subscription#cancel() cancelled} and {@link SubscriptionOption#NOTIFY_CANCELLATION} is
     *       specified when subscribed.</li>
     *   <li>Other exceptions that occurred due to an error while retrieving the elements.</li>
     * </ul>
     *
     * @param executor the executor to subscribe
     * @param options {@link SubscriptionOption}s to subscribe with
     */
    void subscribe(Subscriber<? super T> subscriber, EventExecutor executor, SubscriptionOption... options);

    /**
     * Returns a new {@link StreamMessageDuplicator} that duplicates this {@link StreamMessage} into one or
     * more {@link StreamMessage}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link StreamMessage} anymore after you call this method.
     * To subscribe, call {@link StreamMessageDuplicator#duplicate()} from the returned
     * {@link StreamMessageDuplicator}.
     */
    default StreamMessageDuplicator<T> toDuplicator() {
        return toDuplicator(defaultSubscriberExecutor());
    }

    /**
     * Returns a new {@link StreamMessageDuplicator} that duplicates this {@link StreamMessage} into one or
     * more {@link StreamMessage}s, which publish the same elements.
     * Note that you cannot subscribe to this {@link StreamMessage} anymore after you call this method.
     * To subscribe, call {@link StreamMessageDuplicator#duplicate()} from the returned
     * {@link StreamMessageDuplicator}.
     *
     * @param executor the executor to duplicate
     */
    default StreamMessageDuplicator<T> toDuplicator(EventExecutor executor) {
        requireNonNull(executor, "executor");
        return new DefaultStreamMessageDuplicator<>(this, unused -> 0, executor, 0 /* no limit for length */);
    }

    /**
     * Returns the default {@link EventExecutor} which will be used when a user subscribes using
     * {@link #subscribe(Subscriber)}, {@link #subscribe(Subscriber, SubscriptionOption...)}.
     *
     * <p>Please note that if this method is called multiple times, the returned {@link EventExecutor}s can be
     * different depending on this {@link StreamMessage} implementation.
     */
    default EventExecutor defaultSubscriberExecutor() {
        final EventLoop eventExecutor = RequestContext.mapCurrent(RequestContext::eventLoop,
                                                                  CommonPools.workerGroup()::next);
        assert eventExecutor != null;
        return eventExecutor;
    }

    /**
     * Closes this stream with {@link AbortedStreamException} and prevents further subscription.
     * A {@link Subscriber} that attempts to subscribe to an aborted stream will be notified with
     * an {@link AbortedStreamException} via {@link Subscriber#onError(Throwable)}. Calling this method
     * on a closed or aborted stream has no effect.
     */
    void abort();

    /**
     * Closes this stream with the specified {@link Throwable} and prevents further subscription.
     * A {@link Subscriber} that attempts to subscribe to an aborted stream will be notified with
     * the specified {@link Throwable} via {@link Subscriber#onError(Throwable)}. Calling this method
     * on a closed or aborted stream has no effect.
     */
    void abort(Throwable cause);

    /**
     * Filters values emitted by this {@link StreamMessage}.
     * If the {@link Predicate} test succeeds, the value is emitted.
     * If the {@link Predicate} test fails, the value is ignored and a request of {@code 1} is made to upstream.
     */
    default StreamMessage<T> filter(Predicate<? super T> predicate) {
        requireNonNull(predicate, "predicate");
        return FuseableStreamMessage.of(this, predicate);
    }

    /**
     * Transforms values emitted by this {@link StreamMessage} by applying the specified {@link Function}.
     * As per
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#2.13">
     * Reactive Streams Specification 2.13</a>, the specified {@link Function} should not return
     * a {@code null} value.
     */
    default <U> StreamMessage<U> map(Function<? super T, ? extends U> function) {
        requireNonNull(function, "function");
        if (function == Function.identity()) {
            @SuppressWarnings("unchecked")
            final StreamMessage<U> cast = (StreamMessage<U>) this;
            return cast;
        }

        return FuseableStreamMessage.of(this, function);
    }
}
