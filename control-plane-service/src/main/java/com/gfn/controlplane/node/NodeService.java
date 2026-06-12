package com.gfn.controlplane.node;

import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NodeService {
    private final ConcurrentHashMap<String, GpuNode> nodes = new ConcurrentHashMap<>();

    public NodeResponse register(RegisterNodeRequest request) {
        GpuNode node = new GpuNode(request);
        nodes.put(request.nodeId(), node);
        return NodeResponse.from(node);
    }

    public NodeResponse heartbeat(String nodeId, HeartbeatRequest request) {
        GpuNode node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node: " + nodeId);
        }
        node.heartbeat(request);
        return NodeResponse.from(node);
    }

    public List<GpuNode> findHealthyNodes(Region region, GpuProfile gpuProfile) {
        return nodes.values().stream()
                .filter(node -> node.region() == region)
                .filter(node -> node.gpuProfile().ordinal() >= gpuProfile.ordinal())
                .filter(node -> node.status() == NodeStatus.HEALTHY)
                .filter(node -> node.availableSlots() > 0)
                .sorted(Comparator.comparing(GpuNode::nodeId))
                .toList();
    }

    public Optional<GpuNode> findById(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public List<NodeResponse> listNodes() {
        return nodes.values().stream().map(NodeResponse::from).toList();
    }

    public void markStaleNodes(Duration heartbeatTimeout) {
        Instant cutoff = Instant.now().minus(heartbeatTimeout);
        nodes.values().stream()
                .filter(node -> node.lastHeartbeatAt().isBefore(cutoff))
                .forEach(GpuNode::markStale);
    }
}

