package io.kestra.core.topologies;

import io.kestra.core.models.Label;
import io.kestra.core.models.conditions.Condition;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.FlowWithSource;
import io.kestra.core.models.hierarchies.Graph;
import io.kestra.core.models.tasks.ExecutableTask;
import io.kestra.core.models.topologies.FlowNode;
import io.kestra.core.models.topologies.FlowRelation;
import io.kestra.core.models.topologies.FlowTopology;
import io.kestra.core.models.topologies.FlowTopologyGraph;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.repositories.FlowTopologyRepositoryInterface;
import io.kestra.core.services.ConditionService;
import io.kestra.core.utils.ListUtils;
import io.kestra.plugin.core.condition.*;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Singleton
public class FlowTopologyService {
    public static final Label SIMULATED_EXECUTION = new Label(Label.SYSTEM_PREFIX + "simulatedExecution", "true");

    @Inject
    protected ConditionService conditionService;

    @Inject
    private FlowRepositoryInterface flowRepository;

    @Inject
    private FlowTopologyRepositoryInterface flowTopologyRepository;

    public FlowTopologyGraph graph(Stream<FlowTopology> flows, Function<FlowNode, FlowNode> anonymize) {
        Graph<FlowNode, FlowRelation> graph = new Graph<>();

        flows
            .forEach(flowTopology -> {
                FlowNode source = anonymize.apply(flowTopology.getSource());
                FlowNode destination = anonymize.apply(flowTopology.getDestination());


                if (!graph.nodes().contains(source)) {
                    graph.addNode(source);
                }

                if (!graph.nodes().contains(destination)) {
                    graph.addNode(destination);
                }

                if (!source.getUid().equals(destination.getUid())) {
                    graph.addEdge(source, destination, flowTopology.getRelation());
                }
            });

        return FlowTopologyGraph.of(graph);
    }

    public FlowTopologyGraph namespaceGraph(String tenantId, String namespace) {
        List<FlowTopology> flowTopologies = flowTopologyRepository.findByNamespace(tenantId, namespace);

        FlowTopologyGraph graph = this.graph(flowTopologies.stream(), (flowNode -> flowNode));

        List<String> flowInGraph = graph.
            getNodes()
            .stream()
            .map(FlowNode::getId)
            .distinct()
            .toList();

        Set<FlowNode> existingNodes = new HashSet<>(graph
            .getNodes()
            .stream()
            .collect(Collectors.toMap(node -> node.getId() + "_" + node.getNamespace(), Function.identity(), (node1, node2) -> node1))
            .values()
        );

        Set<FlowNode> newNodes = new HashSet<>();

        flowRepository.findByNamespace(tenantId, namespace).forEach(flow -> {
            if (flowInGraph.contains(flow.getId())) {
                return;
            }

            FlowNode flowNode = FlowNode.builder()
                .id(flow.getId())
                .uid(flow.getNamespace() + "_" + flow.getId())
                .namespace(flow.getNamespace())
                .build();

            newNodes.add(flowNode);
        });

        Set<FlowNode> updatedNodes = new HashSet<>(existingNodes);
        updatedNodes.addAll(newNodes);

        return FlowTopologyGraph.builder()
            .nodes(updatedNodes)
            .edges(graph.getEdges())
            .build();
    }

    public Stream<FlowTopology> topology(FlowWithSource child, List<FlowWithSource> allFlows) {
        return allFlows.stream()
            .flatMap(parent -> Stream.concat(
                Stream.ofNullable(this.map(parent, child)),
                Stream.ofNullable(this.map(child, parent))
            ))
            .filter(Objects::nonNull);
    }

    protected FlowTopology map(FlowWithSource parent, FlowWithSource child) {
        // we don't allow self link
        if (child.uidWithoutRevision().equals(parent.uidWithoutRevision())) {
            return null;
        }

        FlowRelation relation = this.isChild(parent, child);
        if (relation == null) {
            return null;
        }

        FlowNode parentTopology = FlowNode.of(parent);
        FlowNode childTopology = FlowNode.of(child);

        return FlowTopology.builder()
            .source(parentTopology)
            .destination(childTopology)
            .relation(relation)
            .build();
    }

    @Nullable
    public FlowRelation isChild(FlowWithSource parent, FlowWithSource child) {
        if (this.isFlowTaskChild(parent, child)) {
            return FlowRelation.FLOW_TASK;
        }

        if (this.isTriggerChild(parent, child)) {
            return FlowRelation.FLOW_TRIGGER;
        }

        return null;
    }

    protected boolean isFlowTaskChild(FlowWithSource parent, FlowWithSource child) {
        try {
            return parent
                .allTasksWithChilds()
                .stream()
                .filter(t -> t instanceof ExecutableTask)
                .map(t -> (ExecutableTask<?>) t)
                .anyMatch(t ->
                    t.subflowId() != null && t.subflowId().namespace().equals(child.getNamespace()) && t.subflowId().flowId().equals(child.getId())
                );
        } catch (Exception e) {
            log.warn("Failed to detect flow task on namespace:'" + parent.getNamespace() + "', flowId:'" + parent.getId()  + "'", e);
            return false;
        }
    }

    protected boolean isTriggerChild(FlowWithSource parent, FlowWithSource child) {
        List<AbstractTrigger> triggers = ListUtils.emptyOnNull(child.getTriggers());

        // simulated execution: we add a "simulated" label so conditions can know that the evaluation is for a simulated execution
        Execution execution = Execution.newExecution(parent, (f, e) -> null, List.of(SIMULATED_EXECUTION), Optional.empty());

        // keep only flow trigger
        List<io.kestra.plugin.core.trigger.Flow> flowTriggers = triggers
            .stream()
            .filter(t -> t instanceof io.kestra.plugin.core.trigger.Flow)
            .map(t -> (io.kestra.plugin.core.trigger.Flow) t)
            .toList();

        if (flowTriggers.isEmpty()) {
            return false;
        }

        return flowTriggers
            .stream()
            .flatMap(flow -> ListUtils.emptyOnNull(flow.getConditions()).stream())
            .allMatch(condition -> validateCondition(condition, parent, execution));
    }

    protected boolean validateCondition(Condition condition, FlowWithSource child, Execution execution) {
        if (isFilterCondition(condition)) {
            return true;
        }

        if (condition instanceof io.kestra.core.models.triggers.multipleflows.MultipleCondition multipleCondition) {
            List<Condition> multipleConditions = multipleCondition
                .getConditions()
                .values()
                .stream()
                .filter(c -> !isFilterCondition(c))
                .toList();


            return (multipleConditions
                .stream()
                .filter(c -> !isMandatoryMultipleCondition(c))
                .anyMatch(c -> validateCondition(c, child, execution))
            ) && (
                multipleConditions
                    .stream()
                    .filter(this::isMandatoryMultipleCondition)
                    .allMatch(c -> validateCondition(c, child, execution))
            );
        }

        return this.conditionService.isValid(condition, child, execution);
    }

    protected boolean isMandatoryMultipleCondition(Condition condition) {
        return Stream
            .of(
                Expression.class
            )
            .anyMatch(aClass -> condition.getClass().isAssignableFrom(aClass));
    }

    protected boolean isFilterCondition(Condition condition) {
        return Stream
            .of(
                ExecutionStatus.class,
                DateTimeBetween.class,
                DayWeek.class,
                HasRetryAttempt.class,
                Weekend.class
            )
            .anyMatch(aClass -> condition.getClass().isAssignableFrom(aClass));
    }
}
