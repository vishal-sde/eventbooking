package com.eventbooking.exception;

public class IdempotencyKeyReusedException extends RuntimeException{
    public IdempotencyKeyReusedException(String message){
        super(message);
    }
}
