package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;

final class FaultAsyncHttpClient implements SdkAsyncHttpClient {
  private final SdkAsyncHttpClient delegate;
  private final DynamoDbRequestRecorder recorder;

  FaultAsyncHttpClient(SdkAsyncHttpClient delegate, DynamoDbRequestRecorder recorder) {
    this.delegate = delegate;
    this.recorder = recorder;
  }

  @Override
  public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    CompletableFuture<Void> recording = recorder.transmissionRecordingStarted(request.request());
    AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>();
    result.whenComplete(
        (ignored, error) -> {
          if (result.isCancelled() && pending.get() != null) pending.get().cancel(true);
        });
    try {
      HttpReply replacement = recorder.replacement(request.request());
      if (replacement != null) {
        request.responseHandler().onHeaders(replacement.headers());
        request.responseHandler().onStream(AsyncRequestBody.fromBytes(replacement.body()));
        result.complete(null);
        recording.complete(null);
        return result;
      }
      CompletableFuture<byte[]> body = HttpBodies.collect(request.requestContentPublisher());
      pending.set(body);
      body.whenComplete(
          (bytes, error) -> {
            try {
              if (result.isDone()) return;
              if (error != null) {
                request.responseHandler().onError(error);
                result.completeExceptionally(error);
                return;
              }
              CompletableFuture<Void> actual = delegate.execute(request);
              recorder.transmitted(request.request(), bytes);
              pending.set(actual);
              if (result.isCancelled()) actual.cancel(true);
              actual.whenComplete(
                  (ignored, failure) -> {
                    if (failure == null) result.complete(null);
                    else result.completeExceptionally(failure);
                  });
            } catch (Throwable failure) {
              try {
                request.responseHandler().onError(failure);
              } finally {
                result.completeExceptionally(failure);
              }
            } finally {
              recording.complete(null);
            }
          });
    } catch (Throwable error) {
      try {
        request.responseHandler().onError(error);
      } finally {
        result.completeExceptionally(error);
        recording.complete(null);
      }
    }
    return result;
  }

  @Override
  public void close() {
    delegate.close();
  }
}
