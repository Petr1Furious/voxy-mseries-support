#define SENTINAL_OUT_OF_BOUNDS uint(-1)
#ifndef NODE_QUEUE_CAPACITY
#define NODE_QUEUE_CAPACITY 200000
#endif

#ifdef VOXY_VULKAN
layout(binding = NODE_QUEUE_INDEX_BINDING, std140) uniform QueueParameters { uint queueIdx; };
#else
layout(location = NODE_QUEUE_INDEX_BINDING) uniform uint queueIdx;
#endif

layout(binding = NODE_QUEUE_META_BINDING, std430) restrict buffer NodeQueueMeta {
    uvec4 nodeQueueMetadata[MAX_ITERATIONS];
};

layout(binding = NODE_QUEUE_SOURCE_BINDING, std430) restrict readonly buffer NodeQueueSource {
    uint[] nodeQueueSource;
};

layout(binding = NODE_QUEUE_SINK_BINDING, std430) restrict writeonly buffer NodeQueueSink {
    uint[] nodeQueueSink;
};

uint getCurrentNode() {
    if (nodeQueueMetadata[queueIdx].w <= gl_GlobalInvocationID.x) {
        return SENTINAL_OUT_OF_BOUNDS;
    }
    if (gl_GlobalInvocationID.x >= NODE_QUEUE_CAPACITY) return SENTINAL_OUT_OF_BOUNDS;
    return nodeQueueSource[gl_GlobalInvocationID.x];
}


//TODO: limit the size/writing out of bounds
uint nodePushIndex = -1;
bool pushNodesInit(uint nodeCount) {
    if (queueIdx >= MAX_ITERATIONS-1) return false;
    //Debug
    #ifdef DEBUG
    if (queueIdx >= (MAX_ITERATIONS-1)) {
        printf("LOG: Traversal tried inserting a node into next iteration, which is outside max iteration bounds. GID: %d, count: %d", gl_GlobalInvocationID.x, nodeCount);
        nodePushIndex = -1;
        return false;
    }
    #endif

    uint index = atomicAdd(nodeQueueMetadata[queueIdx+1].w, 0u);
    while (true) {
        if (index > NODE_QUEUE_CAPACITY || nodeCount > NODE_QUEUE_CAPACITY-index) return false;
        uint previous = atomicCompSwap(nodeQueueMetadata[queueIdx+1].w, index, index+nodeCount);
        if (previous == index) break;
        index = previous;
    }
    uint inc = ((index+nodeCount+LOCAL_SIZE-1)>>LOCAL_SIZE_BITS)-((index+LOCAL_SIZE-1)>>LOCAL_SIZE_BITS);
    atomicAdd(nodeQueueMetadata[queueIdx+1].x, inc);
    nodePushIndex = index;
    return true;
}

void pushNode(uint nodeId) {
    #ifdef DEBUG
    if (nodePushIndex == -1) {
        printf("LOG: Tried pushing node when push node wasnt successful. GID: %d, pushing: %d", gl_GlobalInvocationID.x, nodeId);
        return;
    }
    #endif
    nodeQueueSink[nodePushIndex++] = nodeId;
}

#define SIMPLE_QUEUE(type, name, bindingIndex) layout(binding = bindingIndex, std430) restrict buffer name##Struct { \
    type name##Index; \
    type##[] name; \
};
