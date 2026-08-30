package cm.identishield.pdp.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduit une requête hors contrat en 400 {code: CONTRACT_VIOLATION} (le corps d'erreur du
 * contrat). Une requête sans certificat client valide est déjà rejetée à la poignée de main mTLS
 * (OB-5), avant d'atteindre ce niveau.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiError onValidation(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
            .orElse("contract violation");
    return new ApiError("CONTRACT_VIOLATION", message);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiError onUnreadable(HttpMessageNotReadableException e) {
    return new ApiError("CONTRACT_VIOLATION", "malformed request body");
  }
}
