package com.timetable.operator.common.api;

public class UserActionRequiredException extends RuntimeException {

    public UserActionRequiredException(String message) {
        super(message);
    }
}
