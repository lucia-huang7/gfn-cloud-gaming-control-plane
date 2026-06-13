package com.gfn.controlplane.placement;

import com.gfn.controlplane.node.GpuNode;
import com.gfn.controlplane.node.NodeService;
import com.gfn.controlplane.session.CreateSessionRequest;
import org.springframework.stereotype.Service;

import java.util.Comparator;

@Service
public class PlacementService {
    private final NodeService nodeService;
    private final CapacityScorer scorer;
    private final RedisLeaseManager leaseManager;

    public PlacementService(NodeService nodeService, CapacityScorer scorer, RedisLeaseManager leaseManager) {
        this.nodeService = nodeService;
        this.scorer = scorer;
        this.leaseManager = leaseManager;
    }

    public PlacementResult place(String sessionId, CreateSessionRequest request) {
        return nodeService.findHealthyNodes(request.region(), request.gpuProfile()).stream()
                .sorted(Comparator.comparingDouble((GpuNode node) -> scorer.score(node, request)).reversed())
                .filter(node -> leaseManager.tryReserve(node, sessionId))
                .findFirst()
                .map(node -> {
                    nodeService.markReserved(node.nodeId());
                    return PlacementResult.reserved(sessionId, node.nodeId());
                })
                .orElseGet(() -> PlacementResult.queued(sessionId));
    }

    public void release(String nodeId, String sessionId) {
        leaseManager.release(nodeId, sessionId);
        nodeService.markReleased(nodeId);
    }
}
