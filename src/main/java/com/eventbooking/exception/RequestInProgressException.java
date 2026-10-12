package com.eventbooking.exception;

public class RequestInProgressException extends RuntimeException{
    public RequestInProgressException(String message){
        super(message);
    }
}
