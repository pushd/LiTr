/*
 * Copyright 2019 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Licensed under the BSD 2-Clause License (the "License").  See License in the project root for
 * license information.
 */
package com.linkedin.android.litr;

import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;

import com.linkedin.android.litr.analytics.TrackTransformationInfo;

import java.util.List;

/**
 * A listener interface, to notify client about transformation progress/result
 * Callbacks are made on UI thread, to make it safe to update UI in listener implementations
 */
public interface TransformationListener {

    /**
     * Transformation started successfully
     * @param id request id
     */
    void onStarted(@Nullable String id);

    /**
     * Transformation progress update
     * @param id request id
     * @param progress progress, from 0 to 1, with client specified granularity
     */
    void onProgress(@Nullable String id, @FloatRange(from = 0, to = 1) float progress);

    /**
     * Transformation completed
     * @param id request id
     */
    void onCompleted(@Nullable String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos);

    /**
     * Transformation was cancelled
     * @param id request id
     */
    void onCancelled(@Nullable String id, @Nullable List<TrackTransformationInfo> trackTransformationInfos);

    /**
     * Transformation error
     * @param id request id
     * @param cause error cause
     */
    void onError(@Nullable String id, @Nullable Throwable cause, @Nullable List<TrackTransformationInfo> trackTransformationInfos);
}
