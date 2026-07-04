package com.ticketapp.domain.exceptions;


import lombok.NonNull;

public class TicketAlreadyExtractedException extends TicketAppException {

  public TicketAlreadyExtractedException(@NonNull String message, @NonNull String value, @NonNull Integer httpCode) {
    super(message, value, httpCode);
  }
}
