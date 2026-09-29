package com.java_template.common.grpc;

import com.java_template.common.grpc.client.connection.ConnectionManager;
import com.java_template.common.grpc.client.monitoring.GrpcConnectionMonitor;
import io.grpc.ConnectivityState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ABOUTME: Operational endpoints for the Cyoda gRPC connection. They carry no authentication of their own, so
 * they exist only when {@code app.admin.grpc-endpoints.enabled=true} (off by default); enable them only where
 * the app's security configuration protects {@code /admin/**}.
 */
@RestController
@RequestMapping("/admin/grpc")
@ConditionalOnProperty(name = "app.admin.grpc-endpoints.enabled", havingValue = "true")
public class GrpcAdminController {

    private final ConnectionManager connectionManager;
    private final GrpcConnectionMonitor connectionMonitor;


    public GrpcAdminController(
            final ConnectionManager connectionManager,
            final GrpcConnectionMonitor connectionMonitor
    ) {
        this.connectionManager = connectionManager;
        this.connectionMonitor = connectionMonitor;
    }

    /**
     * Restarts reconnection after the client gave up (the channel is IDLE). Any other state is refused: the
     * reconnection strategy would ignore the request anyway, so nothing is "forced".
     */
    @PostMapping("/reconnect")
    public ResponseEntity<String> resurrect() {
        if (connectionMonitor.getLastKnownState().connectionState().equals(ConnectivityState.IDLE)) {
            connectionManager.resurrect();
            return ResponseEntity.ok("Reconnection from IDLE state initiated");
        }
        return ResponseEntity.badRequest().body("Not in idle state");
    }

    @GetMapping("/status")
    public ResponseEntity<GrpcConnectionMonitor.GrpcMonitoringState> getStatus() {
        return ResponseEntity.ok(connectionMonitor.getLastKnownState());
    }

}
