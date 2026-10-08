package jp.cheapreview.bridge.controller;

import jp.cheapreview.api.AppProtocol;
import jp.cheapreview.bridge.dto.BridgeDtos.BridgeInput;
import jp.cheapreview.bridge.dto.BridgeDtos.ConnectionStatus;
import jp.cheapreview.bridge.dto.BridgeDtos.Health;
import jp.cheapreview.bridge.dto.BridgeDtos.PickInput;
import jp.cheapreview.bridge.dto.BridgeDtos.PickResult;
import jp.cheapreview.bridge.dto.BridgeDtos.Registration;
import jp.cheapreview.bridge.dto.BridgeDtos.RegistrationResult;
import jp.cheapreview.bridge.dto.BridgeDtos.RequestView;
import jp.cheapreview.bridge.dto.BridgeDtos.ResultInput;
import jp.cheapreview.bridge.service.BridgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BridgeController {
  private final BridgeService bridge;

  public BridgeController(BridgeService bridge) {
    this.bridge = bridge;
  }

  @GetMapping("/health")
  public Health health() {
    return new Health("UP", AppProtocol.APP_VERSION, AppProtocol.PROTOCOL_VERSION);
  }

  @GetMapping("/status")
  public ConnectionStatus status() {
    return bridge.status();
  }

  @PostMapping("/bridge/register")
  public RegistrationResult register(@RequestBody Registration input) {
    return new RegistrationResult(bridge.register(input));
  }

  @PostMapping("/bridge/heartbeat")
  public ResponseEntity<Void> heartbeat(@RequestBody BridgeInput input) {
    bridge.heartbeat(input.bridgeId());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/bridge/requests")
  public PickResult pick(@RequestBody PickInput input) {
    return new PickResult(bridge.requestFolder(input.kind()));
  }

  @GetMapping("/bridge/requests/next")
  public ResponseEntity<RequestView> next(@RequestParam String bridgeId) {
    return bridge
        .claimNext(bridgeId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/bridge/requests/{id}")
  public RequestView request(@PathVariable String id) {
    return bridge.getRequest(id);
  }

  @PostMapping("/bridge/requests/{id}/result")
  public RequestView result(@PathVariable String id, @RequestBody ResultInput input) {
    return bridge.complete(id, input);
  }
}
