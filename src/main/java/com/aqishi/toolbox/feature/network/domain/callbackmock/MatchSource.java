package com.aqishi.toolbox.feature.network.domain.callbackmock;

/** The part of an incoming request from which a condition or template reads. */
public enum MatchSource {
    QUERY,
    HEADER,
    FORM,
    JSON
}
