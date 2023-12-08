/*
 * Copyright 2019 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Licensed under the BSD 2-Clause License (the "License").  See License in the project root for
 * license information.
 */
package com.linkedin.android.litr.io;

import static com.linkedin.android.litr.exception.MediaSourceException.Error.DATA_SOURCE;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.linkedin.android.litr.exception.MediaSourceException;
import com.linkedin.android.litr.utils.TranscoderUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * An implementation of MediaSource, which wraps Android's {@link MediaExtractor}
 */
public class MediaExtractorMediaSource implements MediaSource {
    private final static String TAG = "MediaExtractorMediaSrc";

    private final MediaExtractor mediaExtractor;
    private final MediaRange mediaRange;
    private long size;

    public MediaExtractorMediaSource(@NonNull Context context, @NonNull Uri uri) throws MediaSourceException {
        this(context, uri, new MediaRange(0, Long.MAX_VALUE));
    }

    public MediaExtractorMediaSource(@NonNull Context context, @NonNull Uri uri, @NonNull MediaRange mediaRange) throws MediaSourceException {
        this(context, uri, mediaRange, TranscoderUtils.getSize(context, uri), false);
    }

    public MediaExtractorMediaSource(@NonNull Context context, @NonNull Uri uri, @NonNull MediaRange mediaRange, long size, boolean isNetworkSource) throws MediaSourceException {
        this.mediaRange = mediaRange;

        mediaExtractor = new MediaExtractor();
        try {
            if (isNetworkSource) {
                mediaExtractor.setDataSource(uri.toString());
            } else {
                mediaExtractor.setDataSource(context, uri, new HashMap<>());
            }
        } catch (IOException ex) {
            throw new MediaSourceException(DATA_SOURCE, uri, ex);
        }

        if (size < 1) {
            this.size = TranscoderUtils.getSize(context, uri);
        } else {
            this.size = size;
        }
    }

    // Irrelevant, may be throw exception?
    @Override
    public int getOrientationHint() {
        Log.e(TAG, "Tried to get video orientation hint on extractor source");
        return 0;
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
}
