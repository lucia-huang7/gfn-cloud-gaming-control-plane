package com.gfn.controlplane.node;

import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class NodeController {
    private final NodeService nodeService;

    public NodeController(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @PostMapping("/nodes/register")
    public NodeResponse register(@Valid @RequestBody RegisterNodeRequest request) {
        return nodeService.register(request);
    }

    @PostMapping("/nodes/{nodeId}/heartbeat")
    public NodeResponse heartbeat(@PathVariable String nodeId, @Valid @RequestBody HeartbeatRequest request) {
        return nodeService.heartbeat(nodeId, request);
    }

    @GetMapping("/nodes")
    public List<NodeResponse> listNodes() {
        return nodeService.listNodes();
    }

    @GetMapping("/capacity")
    public List<NodeResponse> capacity(@RequestParam Region region, @RequestParam GpuProfile gpuProfile) {
        return nodeService.findHealthyNodes(region, gpuProfile).stream().map(NodeResponse::from).toList();
    }
}

