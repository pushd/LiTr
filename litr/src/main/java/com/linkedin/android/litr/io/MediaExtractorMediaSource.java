/*
 * Copyright 2019 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Licensed under the BSD 2-Clause License (the "License").  See License in the project root for
 * license information.
 */
package com.linkedin.android.litr.io;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.linkedin.android.litr.exception.MediaSourceException;
import com.linkedin.android.litr.utils.TranscoderUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;

import static com.linkedin.android.litr.exception.MediaSourceException.Error.DATA_SOURCE;

/**
 * An implementation of MediaSource, which wraps Android's {@link MediaExtractor}
 */
public class MediaExtractorMediaSource implements MediaSource {
    private final static String TAG = "MediaExtractorMediaSrc";

    private final MediaExtractor mediaExtractor;
    private final MediaRange mediaRange;

    private int orientationHint;
    private long size;

    public MediaExtractorMediaSource(@NonNull Context context, @NonNull Uri uri) throws MediaSourceException {
        this(context, uri, new MediaRange(0, Long.MAX_VALUE));
    }

    public MediaExtractorMediaSource(@NonNull Context context, @NonNull Uri uri, @NonNull MediaRange mediaRange) throws MediaSourceException {
        this(context, uri, mediaRange, TranscoderUtils.getSize(context, uri), false, -1, -1);
    }

    public MediaExtractorMediaSource(@NonNull Context context, @NonNull Uri uri, @NonNull MediaRange mediaRange, long size, boolean isNetworkSource, int restrictToHeight, int restrictToWidth) throws MediaSourceException {
        this.mediaRange = mediaRange;

        mediaExtractor = new MediaExtractor();
        MediaMetadataRetriever mediaMetadataRetriever = null;
        try {
            mediaExtractor.setDataSource(context, uri, new HashMap<>());
            mediaMetadataRetriever = new MediaMetadataRetriever();
            if (isNetworkSource) {
                mediaMetadataRetriever.setDataSource(uri.toString(), new HashMap<>());
            } else {
                mediaMetadataRetriever.setDataSource(context, uri);
            }
        } catch (IOException ex) {
            releaseQuietly(mediaMetadataRetriever);
            throw new MediaSourceException(DATA_SOURCE, uri, ex);
        }
        try {
            checkHeightRestriction(mediaMetadataRetriever, restrictToHeight, uri);
            checkWidthRestriction(mediaMetadataRetriever, restrictToWidth, uri);
        } catch (NumberFormatException ex) {
            Log.e(TAG, "Could not check the dimensions of the video source");
        }
        String rotation = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        Log.i(TAG, "Video rotation " + rotation);
        if (rotation != null) {
            orientationHint = Integer.parseInt(rotation);
        }
        // Release unused anymore MediaMetadataRetriever instance
        releaseQuietly(mediaMetadataRetriever);
        if (size < 1) {
            this.size = TranscoderUtils.getSize(context, uri);
        } else {
            this.size = size;
        }
    }

    @Override
    public int getOrientationHint() {
        return orientationHint;
    }

    @Override
    public int getTrackCount() {
        return mediaExtractor.getTrackCount();
    }

    @Override
    @NonNull
    public MediaFormat getTrackFormat(int track) {
        return mediaExtractor.getTrackFormat(track);
    }

    @Override
    public void selectTrack(int track) {
        mediaExtractor.selectTrack(track);
    }

    @Override
    public void seekTo(long position, int mode) {
        mediaExtractor.seekTo(position, mode);
    }

    @Override
    public int getSampleTrackIndex() {
        return mediaExtractor.getSampleTrackIndex();
    }

    @Override
    public int readSampleData(@NonNull ByteBuffer buffer, int offset) {
        return mediaExtractor.readSampleData(buffer, offset);
    }

    @Override
    public long getSampleTime() {
        return mediaExtractor.getSampleTime();
    }

    @Override
    public int getSampleFlags() {
        return mediaExtractor.getSampleFlags();
    }

    @Override
    public void advance() {
        mediaExtractor.advance();
    }

    @Override
    public void release() {
        mediaExtractor.release();
    }

    @Override
    public long getSize() {
        return size;
    }

    @NonNull
    @Override
    public MediaRange getSelection() {
        return mediaRange;
    }

    private void checkHeightRestriction(MediaMetadataRetriever mediaMetadataRetriever, int restrictToHeight, Uri uri) throws MediaSourceException, NumberFormatException {
        if (restrictToHeight > 0) {
            String height = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            Log.i(TAG, "Video height " + height);
            if (height != null) {
                if (Integer.parseInt(height) > restrictToHeight) {
                    throw new MediaSourceException(DATA_SOURCE, uri, new IllegalArgumentException("Video height greater than given restriction of " + restrictToHeight));
                }
            }
        }
    }

    private void checkWidthRestriction(MediaMetadataRetriever mediaMetadataRetriever, int restrictToWidth, Uri uri) throws MediaSourceException, NumberFormatException {
        if (restrictToWidth > 0) {
            String width = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            Log.i(TAG, "Video width " + width);
            if (width != null) {
                if (Integer.parseInt(width) > restrictToWidth) {
                    throw new MediaSourceException(DATA_SOURCE, uri, new IllegalArgumentException("Video width greater than given restriction of " + restrictToWidth));
                }
            }
        }
    }

    private void releaseQuietly(@Nullable MediaMetadataRetriever mediaMetadataRetriever) {
        if (mediaMetadataRetriever == null) return;
        try {
            mediaMetadataRetriever.release();
        } catch (IOException ex) {
            // Nothing to do.
            Log.w(TAG, "Could not release MediaMetadataRetriever, may already be released");
        }
    }
}
