package com.timetable.operator.agent.application;

class AiDateTimeFormatException extends IllegalArgumentException {

    AiDateTimeFormatException(String message) {
        super(message);
    }

    AiDateTimeFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
