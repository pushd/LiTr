/*
 * Copyright 2019 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Licensed under the BSD 2-Clause License (the "License").  See License in the project root for
 * license information.
 */
package com.linkedin.android.litr;

import static com.linkedin.android.litr.exception.MediaSourceException.Error.DATA_SOURCE;

import android.content.Context;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.linkedin.android.litr.codec.Encoder;
import com.linkedin.android.litr.codec.MediaCodecDecoder;
import com.linkedin.android.litr.codec.MediaCodecEncoder;
import com.linkedin.android.litr.exception.MediaSourceException;
import com.linkedin.android.litr.exception.MediaTargetException;
import com.linkedin.android.litr.io.MediaExtractorMediaSource;
import com.linkedin.android.litr.io.MediaMuxerMediaTarget;
import com.linkedin.android.litr.io.MediaSource;
import com.linkedin.android.litr.render.AudioRenderer;
import com.linkedin.android.litr.render.GlVideoRenderer;
import com.linkedin.android.litr.utils.MediaFormatUtils;
import com.linkedin.android.litr.utils.TranscoderUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * This is the main entry point into LiTr. Using it is very straightforward:
 *  - instantiate with a context (usually, application context)
 *  - transform video/audio, make sure to provide unique tag for each transformation
 *  - listen on each transformation using a listener (callbacks happen on UI thread)
 *  - cancel transformation using its tag
 *  - release when you no longer need it
 */
public class MediaTransformer {
    public static final int GRANULARITY_NONE = 0;
    public static final int GRANULARITY_DEFAULT = 100;

    public static final int DEFAULT_KEY_FRAME_INTERVAL = 5;
    private static final int DEFAULT_AUDIO_BITRATE = 256_000;
    private static final int DEFAULT_FRAME_RATE = 30;

    private static final String TAG = MediaTransformer.class.getSimpleName();
    private static final int DEFAULT_FUTURE_MAP_SIZE = 10;

    private final Context context;

    private final ExecutorService executorService;
    private final Looper looper;

    private final Map<String, Future<?>> futureMap;

    /**
     * Instantiate MediaTransformer. Listener callbacks will be done on main UI thread.
     * All transformations will be done on a single thread.
     * @param context context with access to source and target URIs and other resources
     */
    public MediaTransformer(@NonNull Context context) {
        this(context, Looper.getMainLooper(), Executors.newSingleThreadExecutor());
    }

    /**
     * Instantiate MediaTransformer
     * @param context context with access to source and target URIs and other resources
     * @param looper {@link Looper} of a thread to marshal listener callbacks to, null for calling back on an ExecutorService thread.
     * @param executorService {@link ExecutorService} to use for transformation jobs
     */
    public MediaTransformer(@NonNull Context context, @Nullable Looper looper, @Nullable ExecutorService executorService) {
        this.context = context.getApplicationContext();

        futureMap = new HashMap<>(DEFAULT_FUTURE_MAP_SIZE);
        this.looper = looper;
        this.executorService = executorService;
    }

    /**
     * Transform video and audio track(s): change resolution, frame rate, bitrate, etc. Video track transformation
     * uses default hardware accelerated codecs and OpenGL renderer.
     *
     * If overlay(s) are provided, video track(s) will be transcoded with parameters as close to source format as possible.
     *
     * @param requestId client defined unique id for a transformation request. If not unique, {@link IllegalArgumentException} will be thrown.
     * @param inputUri input video {@link Uri}
     * @param outputFilePath Absolute path of output media file
     * @param targetVideoFormat target format parameters for video track(s), null to keep them as is
     * @param targetAudioFormat target format parameters for audio track(s), null to keep them as is
     * @param listener {@link TransformationListener} implementation, to get updates on transformation status/result/progress
     * @param transformationOptions optional instance of {@link TransformationOptions}
     */
    public void transform(@NonNull String requestId,
                          @NonNull Uri inputUri,
                          @NonNull String outputFilePath,
                          @Nullable MediaFormat targetVideoFormat,
                          @Nullable MediaFormat targetAudioFormat,
                          @NonNull TransformationListener listener,
                          @Nullable TransformationOptions transformationOptions) {
        transform(
                requestId,
                inputUri,
                Uri.fromFile(new File(outputFilePath)),
                targetVideoFormat,
                targetAudioFormat,
                listener,
                transformationOptions
        );
    }

    /**
     * Transform video and audio track(s): change resolution, frame rate, bitrate, etc. Video track transformation
     * uses default hardware accelerated codecs and OpenGL renderer.
     *
     * If overlay(s) are provided, video track(s) will be transcoded with parameters as close to source format as possible.
     *
     * This API is recommended to be used on devices with Android 10+ because it works with scoped storage
     *
     * @param requestId client defined unique id for a transformation request. If not unique, {@link IllegalArgumentException} will be thrown.
     * @param inputUri input video {@link Uri}
     * @param outputUri {@link Uri} of transformation output media
     * @param targetVideoFormat target format parameters for video track(s), null to keep them as is
     * @param targetAudioFormat target format parameters for audio track(s), null to keep them as is
     * @param listener {@link TransformationListener} implementation, to get updates on transformation status/result/progress
     * @param transformationOptions optional instance of {@link TransformationOptions}
     */
    public void transform(@NonNull String requestId,
                          @NonNull Uri inputUri,
                          @NonNull Uri outputUri,
                          @Nullable MediaFormat targetVideoFormat,
                          @Nullable MediaFormat targetAudioFormat,
                          @NonNull TransformationListener listener,
                          @Nullable TransformationOptions transformationOptions) {
        TransformationOptions options = transformationOptions == null
                ? new TransformationOptions.Builder().build()
                : transformationOptions;

        try {
            MediaSource mediaSource = new MediaExtractorMediaSource(context, inputUri, options.sourceMediaRange, options.sourceSize, options.isNetworkSource);

            int targetTrackCount = 0;
            for (int track = 0; track < mediaSource.getTrackCount(); track++) {
                if (shouldIncludeTrack(mediaSource.getTrackFormat(track), options.removeAudio, options.removeMetadata)) {
                    targetTrackCount++;
                }
            }

            boolean isVp8OrVp9 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    && targetVideoFormat != null
                    && targetVideoFormat.containsKey(MediaFormat.KEY_MIME)
                    && (TextUtils.equals(targetVideoFormat.getString(MediaFormat.KEY_MIME), MediaFormat.MIMETYPE_VIDEO_VP9)
                    || TextUtils.equals(targetVideoFormat.getString(MediaFormat.KEY_MIME), MediaFormat.MIMETYPE_VIDEO_VP8));

            int outputFormat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && isVp8OrVp9
                    ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                    : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

            if (targetTrackCount > 0) {
                MediaMuxerMediaTarget mediaTarget = new MediaMuxerMediaTarget(
                        context,
                        outputUri,
                        targetTrackCount,
                        -1, // set rotation/orientation to -1, will update when we have it as part of video track format later
                        outputFormat);

                int trackCount = mediaSource.getTrackCount();
                List<TrackTransform> trackTransforms = new ArrayList<>(trackCount);
                for (int track = 0; track < trackCount; track++) {
                    MediaFormat sourceMediaFormat = mediaSource.getTrackFormat(track);

                    String mimeType = null;
                    if (sourceMediaFormat.containsKey(MediaFormat.KEY_MIME)) {
                        mimeType = sourceMediaFormat.getString(MediaFormat.KEY_MIME);
                    }

                    if (mimeType == null) {
                        throw new MediaSourceException(DATA_SOURCE, inputUri, new IllegalArgumentException("Video source mime type unknown"));
                    }

                    boolean isVideoTrack = mimeType.startsWith("video");
                    boolean isAudioTrack = mimeType.startsWith("audio");

                    if (isVideoTrack) {
                        if (options.restrictToHeight > 0 && sourceMediaFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                            int sourceVideoHeight = sourceMediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
                            Log.i(TAG, "Video height from source format: " + sourceVideoHeight);
                            if (sourceVideoHeight > options.restrictToHeight) {
                                throw new MediaSourceException(DATA_SOURCE, inputUri, new IllegalArgumentException("Video height greater than given restriction of " + options.restrictToHeight));
                            }
                        } else {
                            Log.w(TAG, (options.restrictToHeight > 0 ? "Could not " : "Ignore ") + "check the height of the video source");
                        }

                        if (options.restrictToWidth > 0 && sourceMediaFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                            int sourceVideoWidth = sourceMediaFormat.getInteger(MediaFormat.KEY_WIDTH);
                            Log.i(TAG, "Video width from source format: " + sourceVideoWidth);
                            if (sourceVideoWidth > options.restrictToWidth) {
                                throw new MediaSourceException(DATA_SOURCE, inputUri, new IllegalArgumentException("Video width greater than given restriction of " + options.restrictToWidth));
                            }
                        } else {
                            Log.w(TAG, (options.restrictToWidth > 0 ? "Could not " : "Ignore ") + "check the width of the video source");
                        }

                        if (sourceMediaFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                            int orientationHint = sourceMediaFormat.getInteger(MediaFormat.KEY_ROTATION);
                            Log.i(TAG, "Video rotation from source format: " + orientationHint);
                            mediaTarget.setOrientationHint(orientationHint);
                        } else {
                            Log.i(TAG, "Video rotation not present in source format");
                        }
                    }

                    if (!shouldIncludeTrack(mimeType, options.removeAudio, options.removeMetadata)) {
                        continue;
                    }

                    TrackTransform.Builder trackTransformBuilder = new TrackTransform.Builder(mediaSource, track, mediaTarget)
                            .setTargetTrack(trackTransforms.size());

                    if (isVideoTrack) {
                        trackTransformBuilder.setDecoder(new MediaCodecDecoder())
                                .setRenderer(new GlVideoRenderer(options.videoFilters))
                                .setEncoder(new MediaCodecEncoder())
                                .setTargetFormat(targetVideoFormat);
                    } else if (isAudioTrack) {
                        Encoder encoder = new MediaCodecEncoder();
                        trackTransformBuilder.setDecoder(new MediaCodecDecoder())
                                .setEncoder(encoder)
                                .setRenderer(new AudioRenderer(encoder, options.audioFilters))
                                .setTargetFormat(targetAudioFormat);
                    } else {
                        trackTransformBuilder.setTargetFormat(null);
                    }

                    trackTransforms.add(trackTransformBuilder.build());
                }

                transform(requestId, trackTransforms, listener, options.granularity);
            } else {
                throw new MediaTargetException(
                        MediaTargetException.Error.NO_OUTPUT_TRACKS,
                        outputUri,
                        outputFormat,
                        new IllegalArgumentException("No output tracks left")
                );
            }
        } catch (MediaSourceException | MediaTargetException ex) {
            listener.onError(requestId, ex, null);
        }
    }

    /**
     * Transform using specific track transformation instructions. This allows things muxing/demuxing tracks, applying
     * different transformations to different tracks, etc.
     *
     * If a track renderer has overlay(s), that track will be transcoded with parameters as close to source format as possible.
     *
     * @param requestId client defined unique id for a transformation request. If not unique, {@link IllegalArgumentException} will be thrown.
     * @param trackTransforms list of track transformation instructions
     * @param listener {@link TransformationListener} implementation, to get updates on transformation status/result/progress
     * @param granularity progress reporting granularity. NO_GRANULARITY for per-frame progress reporting,
     *                    or positive integer value for number of times transformation progress should be reported
     */
    public void transform(@NonNull String requestId,
                          List<TrackTransform> trackTransforms,
                          @NonNull TransformationListener listener,
                          @IntRange(from = GRANULARITY_NONE) int granularity) {
        if (futureMap.containsKey(requestId)) {
            throw new IllegalArgumentException("Request with id " + requestId + " already exists");
        }

        int trackCount = trackTransforms.size();

        String targetVideoMimeType = null;
        for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
            TrackTransform trackTransform = trackTransforms.get(trackIndex);
            MediaFormat sourceMediaFormat = trackTransform.getMediaSource().getTrackFormat(trackTransform.getSourceTrack());
            MediaFormat targetMediaFormat = trackTransform.getTargetFormat();
            if (targetMediaFormat != null
                    && targetMediaFormat.containsKey(MediaFormat.KEY_MIME)
                    && targetMediaFormat.getString(MediaFormat.KEY_MIME).startsWith("video")) {
                targetVideoMimeType = targetMediaFormat.getString(MediaFormat.KEY_MIME);
                break;
            } else if (sourceMediaFormat.containsKey(MediaFormat.KEY_MIME)
                    && sourceMediaFormat.getString(MediaFormat.KEY_MIME).startsWith("video")) {
                targetVideoMimeType = sourceMediaFormat.getString(MediaFormat.KEY_MIME);
                break;
            }
        }

        for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
            TrackTransform trackTransform = trackTransforms.get(trackIndex);
            if ((trackTransform.getRenderer() != null && trackTransform.getRenderer().hasFilters()
                    || isAudioIncompatible(trackTransform.getMediaSource(), trackTransform.getSourceTrack(), targetVideoMimeType))) {
                MediaFormat targetFormat;
                if (trackTransform.getTargetFormat() == null) {
                    // target format is null, but track has overlays, which means that we cannot use passthrough transcoder
                    // so we transcode the track using source parameters (resolution, bitrate) as a target
                    targetFormat = createTargetMediaFormat(trackTransform.getMediaSource(),
                            trackTransform.getSourceTrack(),
                            targetVideoMimeType);
                } else {
                    targetFormat = trackTransform.getTargetFormat();
                    MediaFormat sourceMediaFormat = trackTransform.getMediaSource().getTrackFormat(trackTransform.getSourceTrack());
                    // make sure the target format has everything needed
                    if (!targetFormat.containsKey(MediaFormat.KEY_HEIGHT) || !targetFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                        // just use from the source
                        targetFormat.setInteger(MediaFormat.KEY_WIDTH, sourceMediaFormat.getInteger(MediaFormat.KEY_WIDTH));
                        targetFormat.setInteger(MediaFormat.KEY_HEIGHT, sourceMediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
                    }

                    if (!targetFormat.containsKey(MediaFormat.KEY_MIME)) {
                        targetFormat.setString(MediaFormat.KEY_MIME, sourceMediaFormat.getString(MediaFormat.KEY_MIME));
                    }

                    if (!targetFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        int targetBitrate = TranscoderUtils.estimateVideoTrackBitrate(trackTransform.getMediaSource(), trackTransform.getSourceTrack());
                        targetFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);
                    }

                    int targetKeyFrameInterval = DEFAULT_KEY_FRAME_INTERVAL;
                    if (!targetFormat.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL) && sourceMediaFormat.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) {
                        targetKeyFrameInterval = sourceMediaFormat.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL);
                    }
                    targetFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, targetKeyFrameInterval);

                    if (!targetFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                        targetFormat.setInteger(
                            MediaFormat.KEY_FRAME_RATE,
                            MediaFormatUtils.getFrameRate(sourceMediaFormat, DEFAULT_FRAME_RATE).intValue()
                    );

                }

                if (targetFormat.getString(MediaFormat.KEY_MIME).contains("video")) {
                    // Video decoder on a133 uses block size of 32 and decodes video's frames with width and height a multiple of 32.
                    // If we do not use the target format a multiple of 32 the encoder will fail to encode a frame that is upscaled by the decoder.
                    int videoHeight = targetFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    int videoWidth = targetFormat.getInteger(MediaFormat.KEY_WIDTH);
                    int widthToUse = videoWidth;
                    int heightToUse = videoHeight;
                    if (videoWidth % 32 != 0) {
                        widthToUse = videoWidth + (32 - videoWidth % 32);
                    }
                    if (videoHeight % 32 != 0) {
                        heightToUse = videoHeight + (32 - videoHeight % 32);
                    }
                    Log.i(TAG, "Video output format resolution width x height: " + widthToUse + " x " + heightToUse);
                    targetFormat.setInteger(MediaFormat.KEY_HEIGHT, heightToUse);
                    targetFormat.setInteger(MediaFormat.KEY_WIDTH, widthToUse);
                }

                TrackTransform updatedTrackTransform = new TrackTransform.Builder(trackTransform.getMediaSource(),
                        trackTransform.getSourceTrack(),
                        trackTransform.getMediaTarget())
                        .setTargetTrack(trackTransform.getTargetTrack())
                        .setDecoder(trackTransform.getDecoder())
                        .setEncoder(trackTransform.getEncoder())
                        .setRenderer(trackTransform.getRenderer())
                        .setTargetFormat(targetFormat)
                        .build();

                trackTransforms.set(trackIndex, updatedTrackTransform);
            }
        }

        TransformationJob transformationJob = new TransformationJob(requestId,
                                                                    trackTransforms,
                                                                    granularity,
                                                                    new MarshallingTransformationListener(futureMap, listener, looper));
        Future<?> future = executorService.submit(transformationJob);

        futureMap.put(requestId, future);
    }

    /**
     * Cancel a transformation request.
     * @param requestId unique id of a job to be cancelled
     */
    public void cancel(@NonNull String requestId) {
        Future<?> future = futureMap.get(requestId);
        if (future != null && !future.isCancelled() && !future.isDone()) {
            future.cancel(true);
        }
    }

    /**
     * Release all resources, stop threads, etc. Transformer will be unusable after this method is called.
     */
    public void release() {
        executorService.shutdownNow();
    }

    /**
     * Estimates target size of a target video based on provided target formats. If no target audio format is specified,
     * uses 320 Kbps bitrate to estimate audio track size, if cannot extract audio bitrate. If track duration is not available,
     * maximum track duration will be used. If track bitrate cannot be extracted, track will not be used to estimate size.
     * @param inputUri {@link Uri} of a source video
     * @param targetVideoFormat video format for a transformation target
     * @param targetAudioFormat audio format for a transformation target
     * @param transformationOptions optional transformation options (such as source range)
     * @return estimated size of a transcoding target video file in bytes, -1 otherwise
     */
    public long getEstimatedTargetVideoSize(@NonNull Uri inputUri,
                                            @NonNull MediaFormat targetVideoFormat,
                                            @Nullable MediaFormat targetAudioFormat,
                                            @Nullable TransformationOptions transformationOptions) {
        try {
            MediaSource mediaSource = transformationOptions == null
            ? new MediaExtractorMediaSource(context, inputUri)
            : new MediaExtractorMediaSource(context, inputUri, transformationOptions.sourceMediaRange);
            return TranscoderUtils.getEstimatedTargetVideoFileSize(mediaSource, targetVideoFormat, targetAudioFormat);
        } catch (MediaSourceException ex) {
            return -1;
        }
    }

    /**
     * Estimates target size of a target video based on track transformations. If no target audio format is specified,
     * uses 320 Kbps bitrate to estimate audio track size, if cannot extract audio bitrate. If track duration is not available,
     * maximum track duration will be used. If track bitrate cannot be extracted, track will not be used to estimate size.
     * @param trackTransforms track transforms
     */
    public long getEstimatedTargetVideoSize(@NonNull List<TrackTransform> trackTransforms) {
        return TranscoderUtils.getEstimatedTargetFileSize(trackTransforms);
    }

    private boolean shouldIncludeTrack(@NonNull MediaFormat sourceMediaFormat, boolean removeAudio, boolean removeMetadata) {
        String mimeType = null;
        if (sourceMediaFormat.containsKey(MediaFormat.KEY_MIME)) {
            mimeType = sourceMediaFormat.getString(MediaFormat.KEY_MIME);
        }

        return shouldIncludeTrack(mimeType, removeAudio, removeMetadata);
    }

    private boolean shouldIncludeTrack(@Nullable String mimeType, boolean removeAudio, boolean removeMetadata) {
        if (mimeType == null) {
            Log.e(TAG, "Mime type is null for track ");
            return false;
        }

        return !(removeAudio && mimeType.startsWith("audio")
                || removeMetadata && !mimeType.startsWith("video") && !mimeType.startsWith("audio"));
    }

    private boolean isAudioIncompatible(@NonNull MediaSource mediaSource,
                                        int sourceTrackIndex,
                                        @Nullable String targetVideoMimeType) {
        if (targetVideoMimeType == null) {
            // most likely no video track
            return false;
        }
        MediaFormat sourceMediaFormat = mediaSource.getTrackFormat(sourceTrackIndex);
        switch (targetVideoMimeType) {
            case MimeType.VIDEO_AVC:
            case MimeType.VIDEO_HEVC:
                return sourceMediaFormat.containsKey(MediaFormat.KEY_MIME)
                        && TextUtils.equals(sourceMediaFormat.getString(MediaFormat.KEY_MIME), MimeType.AUDIO_RAW);
            case MimeType.VIDEO_VP8:
            case MimeType.VIDEO_VP9:
                return sourceMediaFormat.containsKey(MediaFormat.KEY_MIME)
                        && !(TextUtils.equals(sourceMediaFormat.getString(MediaFormat.KEY_MIME), MimeType.AUDIO_OPUS)
                        || TextUtils.equals(sourceMediaFormat.getString(MediaFormat.KEY_MIME), MimeType.AUDIO_VORBIS));
            default:
                return false;
        }
    }

    @Nullable
    private MediaFormat createTargetMediaFormat(@NonNull MediaSource mediaSource,
                                                int sourceTrackIndex,
                                                @Nullable String targetVideoMimeType) {
        MediaFormat sourceMediaFormat = mediaSource.getTrackFormat(sourceTrackIndex);
        MediaFormat targetMediaFormat = null;

        String mimeType = null;
        if (sourceMediaFormat.containsKey(MediaFormat.KEY_MIME)) {
            mimeType = sourceMediaFormat.getString(MediaFormat.KEY_MIME);
        }

        if (mimeType != null) {
            if (mimeType.startsWith("video")) {
                targetMediaFormat = MediaFormat.createVideoFormat(mimeType,
                                                                  sourceMediaFormat.getInteger(MediaFormat.KEY_WIDTH),
                                                                  sourceMediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
                int targetBitrate = TranscoderUtils.estimateVideoTrackBitrate(mediaSource, sourceTrackIndex);
                targetMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);

                int targetKeyFrameInterval = DEFAULT_KEY_FRAME_INTERVAL;
                if (sourceMediaFormat.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) {
                    targetKeyFrameInterval = sourceMediaFormat.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL);
                }
                targetMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, targetKeyFrameInterval);
//                targetMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 30);

                targetMediaFormat.setInteger(
                        MediaFormat.KEY_FRAME_RATE,
                        MediaFormatUtils.getFrameRate(sourceMediaFormat, DEFAULT_FRAME_RATE).intValue()
                );
            } else if (mimeType.startsWith("audio")) {
                if (isAudioIncompatible(mediaSource, sourceTrackIndex, targetVideoMimeType)) {
                    mimeType = getCompatibleAudioMimeType(targetVideoMimeType);
                }
                targetMediaFormat = MediaFormat.createAudioFormat(mimeType,
                                                                  sourceMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                                                  sourceMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                targetMediaFormat.setInteger(
                        MediaFormat.KEY_BIT_RATE,
                        sourceMediaFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                                ? sourceMediaFormat.getInteger(MediaFormat.KEY_BIT_RATE)
                                : DEFAULT_AUDIO_BITRATE);
                if (sourceMediaFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    targetMediaFormat.setLong(
                            MediaFormat.KEY_DURATION,
                            sourceMediaFormat.getLong(MediaFormat.KEY_DURATION));
                }
            }
        }

        return targetMediaFormat;
    }

    @Nullable
    private String getCompatibleAudioMimeType(@NonNull String videoMimeType) {
        switch (videoMimeType) {
            case MimeType.VIDEO_AVC:
            case MimeType.VIDEO_HEVC:
                return MimeType.AUDIO_AAC;
            case MimeType.VIDEO_VP8:
            case MimeType.VIDEO_VP9:
                return MimeType.AUDIO_OPUS;
        }
        return null;
    }
}
