/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.metadata;

import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.castNonNull;

import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult;
import androidx.media3.extractor.metadata.MetadataDecoder;
import androidx.media3.extractor.metadata.MetadataInputBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.checkerframework.dataflow.qual.SideEffectFree;

/**
 * A renderer for metadata.
 *
 * <p>The renderer can be configured to render metadata as soon as they are available using {@link
 * #MetadataRenderer(MetadataOutput, Looper, MetadataDecoderFactory, boolean)}.
 */
@UnstableApi
public final class MetadataRenderer extends BaseRenderer implements Callback {

  private static final String TAG = "MetadataRenderer";
  private static final int MSG_INVOKE_RENDERER = 1;

  private final MetadataDecoderFactory decoderFactory;
  private final MetadataOutput output;
  @Nullable private final Handler outputHandler;
  private final MetadataInputBuffer buffer;
  private final boolean outputMetadataEarly;

  @Nullable private MetadataDecoder decoder;
  private boolean inputStreamEnded;
  private boolean outputStreamEnded;
  private long subsampleOffsetUs;
  // MIREGO: replaced single pending metaData by a queue
  // that allows us to read the entire stream earlier, preventing a late event
  // from stalling audio/video pipelines before a period change
  // (see ExoPlayerImplInternal: hasReadingPeriodFinishedReading()
  private Queue<Metadata> pendingMetadataQueue = new LinkedList<>();
  private long outputStreamOffsetUs;

  /**
   * Creates an instance that uses {@link MetadataDecoderFactory#DEFAULT} to create {@link
   * MetadataDecoder} instances.
   *
   * @param output The output.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   *     If the output makes use of standard Android UI components, then this should normally be the
   *     looper associated with the application's main thread, which can be obtained using {@link
   *     android.app.Activity#getMainLooper()}. Null may be passed if the output should be called
   *     directly on the player's internal rendering thread.
   */
  public MetadataRenderer(MetadataOutput output, @Nullable Looper outputLooper) {
    this(output, outputLooper, MetadataDecoderFactory.DEFAULT);
  }

  /**
   * Creates an instance.
   *
   * @param output The output.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   *     If the output makes use of standard Android UI components, then this should normally be the
   *     looper associated with the application's main thread, which can be obtained using {@link
   *     android.app.Activity#getMainLooper()}. Null may be passed if the output should be called
   *     directly on the player's internal rendering thread.
   * @param decoderFactory A factory from which to obtain {@link MetadataDecoder} instances.
   */
  public MetadataRenderer(
      MetadataOutput output, @Nullable Looper outputLooper, MetadataDecoderFactory decoderFactory) {
    this(output, outputLooper, decoderFactory, /* outputMetadataEarly= */ false);
  }

  /**
   * Creates an instance.
   *
   * @param output The output.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   *     If the output makes use of standard Android UI components, then this should normally be the
   *     looper associated with the application's main thread, which can be obtained using {@link
   *     android.app.Activity#getMainLooper()}. Null may be passed if the output should be called
   *     directly on the player's internal rendering thread.
   * @param decoderFactory A factory from which to obtain {@link MetadataDecoder} instances.
   * @param outputMetadataEarly Whether the renderer outputs metadata early. When {@code true},
   *     {@link #render} will output metadata as soon as they are available to the renderer,
   *     otherwise {@link #render} will output metadata in sync with the rendering position.
   */
  public MetadataRenderer(
      MetadataOutput output,
      @Nullable Looper outputLooper,
      MetadataDecoderFactory decoderFactory,
      boolean outputMetadataEarly) {
    super(C.TRACK_TYPE_METADATA);
    this.output = Assertions.checkNotNull(output);
    this.outputHandler =
        outputLooper == null ? null : Util.createHandler(outputLooper, /* callback= */ this);
    this.decoderFactory = Assertions.checkNotNull(decoderFactory);
    this.outputMetadataEarly = outputMetadataEarly;
    buffer = new MetadataInputBuffer();
    outputStreamOffsetUs = C.TIME_UNSET;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public @Capabilities int supportsFormat(Format format) {
    if (decoderFactory.supportsFormat(format)) {
      return RendererCapabilities.create(
          format.cryptoType == C.CRYPTO_TYPE_NONE ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_DRM);
    } else {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
  }

  @Override
  protected void onStreamChanged(
      Format[] formats,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId) {
    decoder = decoderFactory.createDecoder(formats[0]);

    // MIREGO begin: queue instead of single obj
    Queue<Metadata> newQueue = new LinkedList<>();
    for (Metadata data : pendingMetadataQueue) {
      newQueue.add(data.copyWithPresentationTimeUs(data.presentationTimeUs + outputStreamOffsetUs - offsetUs));
    }
    pendingMetadataQueue = newQueue; // MIREGO end

    outputStreamOffsetUs = offsetUs;
  }

  @Override
  protected void onPositionReset(long positionUs, boolean joining) {
    pendingMetadataQueue.clear(); // MIREGO
    inputStreamEnded = false;
    outputStreamEnded = false;
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) {
    boolean working = true;
    while (working) {
      readMetadata();
      working = outputMetadata(positionUs);
    }
  }

  /**
   * Iterates through {@code metadata.entries} and checks each one to see if contains wrapped
   * metadata. If it does, then we recursively decode the wrapped metadata. If it doesn't (recursion
   * base-case), we add the {@link Metadata.Entry} to {@code decodedEntries} (output parameter).
   */
  private void decodeWrappedMetadata(Metadata metadata, List<Metadata.Entry> decodedEntries) {
    for (int i = 0; i < metadata.length(); i++) {
      @Nullable Format wrappedMetadataFormat = metadata.get(i).getWrappedMetadataFormat();
      if (wrappedMetadataFormat != null && decoderFactory.supportsFormat(wrappedMetadataFormat)) {
        MetadataDecoder wrappedMetadataDecoder =
            decoderFactory.createDecoder(wrappedMetadataFormat);
        // wrappedMetadataFormat != null so wrappedMetadataBytes must be non-null too.
        byte[] wrappedMetadataBytes =
            Assertions.checkNotNull(metadata.get(i).getWrappedMetadataBytes());
        buffer.clear();
        buffer.ensureSpaceForWrite(wrappedMetadataBytes.length);
        castNonNull(buffer.data).put(wrappedMetadataBytes);
        buffer.flip();
        @Nullable Metadata innerMetadata = wrappedMetadataDecoder.decode(buffer);
        if (innerMetadata != null) {
          // The decoding succeeded, so we'll try another level of unwrapping.
          decodeWrappedMetadata(innerMetadata, decodedEntries);
        }
      } else {
        // Entry doesn't contain any wrapped metadata, so output it directly.
        decodedEntries.add(metadata.get(i));
      }
    }
  }

  @Override
  protected void onDisabled() {
    pendingMetadataQueue.clear(); // MIREGO
    decoder = null;
    outputStreamOffsetUs = C.TIME_UNSET;
  }

  @Override
  public boolean isEnded() {
    return outputStreamEnded;
  }

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public boolean handleMessage(Message msg) {
    switch (msg.what) {
      case MSG_INVOKE_RENDERER:
        invokeRendererInternal((Metadata) msg.obj);
        return true;
      default:
        // Should never happen.
        throw new IllegalStateException();
    }
  }

  private void readMetadata() {
    if (!inputStreamEnded) {
      buffer.clear();
      FormatHolder formatHolder = getFormatHolder();
      @ReadDataResult int result = readSource(formatHolder, buffer, /* readFlags= */ 0);
      Log.v(Log.LOG_LEVEL_VERBOSE3, TAG, "readMetadata result: %d", result);
      if (result == C.RESULT_BUFFER_READ) {
        if (buffer.isEndOfStream()) {
          inputStreamEnded = true;
          Log.v(Log.LOG_LEVEL_VERBOSE2, TAG, "readMetadata buffer.isEndOfStream()");
        } else if (buffer.timeUs >= getLastResetPositionUs()) {
          // Ignore metadata before start position.
          buffer.subsampleOffsetUs = subsampleOffsetUs;
          buffer.flip();
          @Nullable Metadata metadata = castNonNull(decoder).decode(buffer);
          if (metadata != null) {
            List<Metadata.Entry> entries = new ArrayList<>(metadata.length());
            decodeWrappedMetadata(metadata, entries);
            if (!entries.isEmpty()) {
              Metadata expandedMetadata =
                  new Metadata(getPresentationTimeUs(buffer.timeUs), entries);
              pendingMetadataQueue.add(expandedMetadata); // MIREGO
              Log.v(Log.LOG_LEVEL_VERBOSE2, TAG, "readMetadata got metaData: %s (count: %d)", expandedMetadata, metadata.length());
            }
          }
        }
      } else if (result == C.RESULT_FORMAT_READ) {
        subsampleOffsetUs = Assertions.checkNotNull(formatHolder.format).subsampleOffsetUs;
      }
    }
  }

  private boolean outputMetadata(long positionUs) {
    boolean didOutput = false;
    // MIREGO modified function to handle metaData queue
    Metadata pendingMetadata = pendingMetadataQueue.peek();
    if (pendingMetadata != null
        && (outputMetadataEarly
            || pendingMetadata.presentationTimeUs <= getPresentationTimeUs(positionUs))) {
      Log.v(Log.LOG_LEVEL_VERBOSE2, TAG, "outputMetadata positionUs: %d metaData: %s", positionUs, pendingMetadata);
      pendingMetadataQueue.poll();
      invokeRenderer(pendingMetadata);
      didOutput = true;
    }
    if (inputStreamEnded && pendingMetadataQueue.isEmpty()) {
      outputStreamEnded = true;
    }
    return didOutput;
  }

  private void invokeRenderer(Metadata metadata) {
    if (outputHandler != null) {
      outputHandler.obtainMessage(MSG_INVOKE_RENDERER, metadata).sendToTarget();
    } else {
      invokeRendererInternal(metadata);
    }
  }

  private void invokeRendererInternal(Metadata metadata) {
    output.onMetadata(metadata);
  }

  @SideEffectFree
  private long getPresentationTimeUs(long positionUs) {
    checkState(positionUs != C.TIME_UNSET);
    checkState(outputStreamOffsetUs != C.TIME_UNSET);

    return positionUs - outputStreamOffsetUs;
  }
}
