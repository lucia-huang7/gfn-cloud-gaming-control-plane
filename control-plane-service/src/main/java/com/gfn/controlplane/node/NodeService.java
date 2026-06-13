package com.gfn.controlplane.node;

import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import com.gfn.controlplane.state.NodeSnapshot;
import com.gfn.controlplane.state.RedisStateStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class NodeService {
    private final RedisStateStore stateStore;

    public NodeService(RedisStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public NodeResponse register(RegisterNodeRequest request) {
        GpuNode node = new GpuNode(request);
        stateStore.saveNode(toSnapshot(node));
        stateStore.setNodeAvailableSlots(node.nodeId(), node.availableSlots());
        return NodeResponse.from(node);
    }

    public NodeResponse heartbeat(String nodeId, HeartbeatRequest request) {
        GpuNode node = findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown node: " + nodeId));
        node.heartbeat(request);
        stateStore.saveNode(toSnapshot(node));
        stateStore.setNodeAvailableSlots(nodeId, node.availableSlots());
        return NodeResponse.from(node);
    }

    public List<GpuNode> findHealthyNodes(Region region, GpuProfile gpuProfile) {
        return stateStore.listNodes().stream()
                .map(this::fromSnapshot)
                .filter(node -> node.region() == region)
                .filter(node -> node.gpuProfile().ordinal() >= gpuProfile.ordinal())
                .filter(node -> node.status() == NodeStatus.HEALTHY)
                .filter(node -> node.availableSlots() > 0)
                .sorted(Comparator.comparing(GpuNode::nodeId))
                .toList();
    }

    public Optional<GpuNode> findById(String nodeId) {
        return stateStore.findNode(nodeId).map(this::fromSnapshot);
    }

    public List<NodeResponse> listNodes() {
        return stateStore.listNodes().stream().map(this::fromSnapshot).map(NodeResponse::from).toList();
    }

    public void markStaleNodes(Duration heartbeatTimeout) {
        Instant cutoff = Instant.now().minus(heartbeatTimeout);
        stateStore.listNodes().stream()
                .map(this::fromSnapshot)
                .filter(node -> node.lastHeartbeatAt().isBefore(cutoff))
                .forEach(node -> {
                    node.markStale();
                    stateStore.saveNode(toSnapshot(node));
                });
    }

    private NodeSnapshot toSnapshot(GpuNode node) {
        return new NodeSnapshot(
                node.nodeId(),
                node.region(),
                node.gpuProfile(),
                node.totalSlots(),
                node.availableSlots(),
                node.activeSessions(),
                node.avgLatencyMs(),
                node.lastHeartbeatAt(),
                node.status()
        );
    }

    private GpuNode fromSnapshot(NodeSnapshot node) {
        int availableSlots = stateStore.getNodeAvailableSlots(node.nodeId()).orElse(node.availableSlots());
        int activeSessions = Math.max(0, node.totalSlots() - availableSlots);
        return new GpuNode(
                node.nodeId(),
                node.region(),
                node.gpuProfile(),
                node.totalSlots(),
                availableSlots,
                activeSessions,
                node.avgLatencyMs(),
                node.lastHeartbeatAt(),
                node.status()
        );
    }
}
