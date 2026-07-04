package com.ticketapp.domain.exceptions;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

@Getter
@ToString
@EqualsAndHashCode(callSuper = false)
public class TicketAppException extends Exception{
  private final String value;

  private final Integer httpCode;

  public TicketAppException(
    @NonNull final String message,
    @NonNull final String value,
    @NonNull final Integer httpCode) {
    super(message);
    this.value = value;
    this.httpCode = httpCode;
  }
}
