package com.fieldservice.photo.domain;

/** Classification of a work order photo at the point of capture. */
public enum PhotoCategory {
    /** Documents the fault or issue found on site. */
    ISSUE,
    /** Documents the completed repair or resolution. */
    COMPLETION
}
