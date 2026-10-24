package cm.identishield.pdp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cm.identishield.pdp.device.DeviceEnrolment;
import cm.identishield.pdp.device.DeviceEnrolment.Refusal;
import cm.identishield.pdp.device.DeviceEnrolment.Refused;
import cm.identishield.pdp.device.DeviceEnrolment.Registered;
import cm.identishield.pdp.device.DeviceRegistry;
import cm.identishield.pdp.device.DeviceRegistry.BindingState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Routes internes du PDP (contracts/device.yaml) : statuts et codes d'erreur du contrat. Tout échec
 * d'activation reçoit la MÊME réponse (401 RECOVERY_INVALID).
 *
 * <p>@covers ES-18
 *
 * <p>@covers ES-22
 */
class DeviceControllerTest {

  private final DeviceEnrolment enrolment = mock(DeviceEnrolment.class);
  private final DeviceRegistry registry = mock(DeviceRegistry.class);
  private final DeviceController controller = new DeviceController(enrolment, registry);

  private final RegisterRequest register =
      new RegisterRequest("acc-1", "cGs=", List.of("Y2VydA=="), "ZGVmaQ==", "c2ln");

  private void willRefuse(Refusal reason) {
    when(enrolment.register("acc-1", "cGs=", List.of("Y2VydA=="), "ZGVmaQ==", "c2ln"))
        .thenReturn(new Refused(reason));
  }

  @Test
  void registered_is200_withDeviceIdAndCodeOnce() {
    when(enrolment.register("acc-1", "cGs=", List.of("Y2VydA=="), "ZGVmaQ==", "c2ln"))
        .thenReturn(new Registered("dev-1", "K7MQ3X"));
    ResponseEntity<Object> r = controller.register(register);
    assertEquals(200, r.getStatusCode().value());
    assertEquals(new RegisterResponse("dev-1", "K7MQ3X"), r.getBody());
  }

  @Test
  void refusals_followTheContract() {
    willRefuse(Refusal.ALREADY_BOUND);
    ResponseEntity<Object> bound = controller.register(register);
    assertEquals(409, bound.getStatusCode().value());
    assertEquals("DEVICE_ALREADY_BOUND", ((ApiError) bound.getBody()).code());

    willRefuse(Refusal.ATTESTATION_REJECTED);
    ResponseEntity<Object> rejected = controller.register(register);
    assertEquals(422, rejected.getStatusCode().value());
    assertEquals("ATTESTATION_REJECTED", ((ApiError) rejected.getBody()).code());

    willRefuse(Refusal.DEVICE_UNSUPPORTED);
    ResponseEntity<Object> unsupported = controller.register(register);
    assertEquals(422, unsupported.getStatusCode().value());
    assertEquals("DEVICE_UNSUPPORTED", ((ApiError) unsupported.getBody()).code());
  }

  @Test
  void state_statesTheFact_noneOrPendingOrActive_andDecidesNothing() {
    when(registry.bindingState("acc-1")).thenReturn(BindingState.ACTIVE);
    when(registry.bindingState("acc-2")).thenReturn(BindingState.PENDING);
    when(registry.bindingState("acc-3")).thenReturn(BindingState.NONE);
    assertEquals(new DeviceStateResponse("ACTIVE"), controller.state("acc-1").getBody());
    assertEquals(new DeviceStateResponse("PENDING"), controller.state("acc-2").getBody());
    assertEquals(new DeviceStateResponse("NONE"), controller.state("acc-3").getBody());
    assertEquals(400, controller.state(" ").getStatusCode().value());
  }

  @Test
  void activation_200_orTheSingleFailure401() {
    when(enrolment.activate("acc-1", "dev-1", "K7MQ3X")).thenReturn(true);
    ResponseEntity<Object> ok =
        controller.activate(new ActivateRequest("acc-1", "dev-1", "K7MQ3X"));
    assertEquals(200, ok.getStatusCode().value());
    assertEquals(new ActivateResponse("dev-1"), ok.getBody());

    ResponseEntity<Object> ko =
        controller.activate(new ActivateRequest("acc-1", "dev-1", "WRONG1"));
    assertEquals(401, ko.getStatusCode().value());
    assertEquals("RECOVERY_INVALID", ((ApiError) ko.getBody()).code());
  }
}
