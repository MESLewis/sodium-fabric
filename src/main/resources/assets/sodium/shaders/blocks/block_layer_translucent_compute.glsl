#version 420 core

#extension GL_ARB_shader_storage_buffer_object : require
#extension GL_ARB_compute_shader : require

#define DUMMY_INDEX 10000000
#define DUMMY_DISTANCE -1000000

//TODO set these at compile time
#define LOCAL_BMS 0
#define LOCAL_DISPERSE 1
#define GLOBAL_FLIP 2
#define GLOBAL_DISPERSE 3

// Note that there exist hardware limits -
// Look these up for your GPU via https://opengl.gpuinfo.org/
//
// sizeof(local_value[LOCAL_SIZE_X]) : Must be <= maxComputeSharedMemorySize
// LOCAL_SIZE_X/2  		             : Must be <= maxComputeWorkGroupInvocations

layout(local_size_x = LOCAL_SIZE_X) in;

struct DrawParameters {
// Older AMD drivers can't handle vec3 in std140 layouts correctly
// The alignment requirement is 16 bytes (4 float components) anyways, so we're not wasting extra memory with this,
// only fixing broken drivers.
    vec4 Offset;
};


//Define packed vertex data
struct Packed {
    uint a_Pos1; //ushort[2] //x,y //The position of the vertex around the model origin
    uint a_Pos2; //ushort[2] //z,w
    uint a_Color; //The color of the vertex
    uint a_TexCoord; // The block texture coordinate of the vertex
    uint a_LightCoord; // The light texture coordinate of the vertex
};

struct IndexGroup {
    uint i1;
    uint i2;
    uint i3;
};

struct ChunkMultiDrawRange {
    uint DataOffset; //Offset into the MultiDrawEntry array that this chunk starts
    uint DataCount; //How many entries in the MultiDrawEntry array this chunk covers
    uint DataIndexCount; //The count of all indicies referenced by this chunk.
};

uniform mat4 u_ModelViewMatrix;
uniform float u_ModelScale;
uniform float u_ModelOffset;
uniform int u_IndexOffsetStride = 12; //Number of bits referenced per array entry
uniform int u_IndexLengthStride = 3; //Number of vertices referenced per array entry
uniform int u_ExecutionType;
uniform int u_ChunkNum;
uniform int u_SortHeight;


layout(std140, binding = 0) uniform ubo_DrawParameters {
    DrawParameters Chunks[256];
};

layout(std430, binding = 1) readonly buffer region_mesh_buffer {
    Packed region_mesh[];
};

layout(std430, binding = 2) buffer region_index_buffer {
    IndexGroup region_index_groups[];
};

layout(std430, binding = 3) readonly buffer chunk_sub_count {
    ChunkMultiDrawRange chunkMultiDrawRange[];
};

layout(std430, binding = 4) readonly buffer index_offset_buffer {
    int indexOffset[];
};

layout(std430, binding = 5) readonly buffer index_length_buffer {
    int indexLength[];
};

layout(std430, binding = 6) readonly buffer vertex_offset_buffer {
    int vertexOffset[];
};

struct IndexDistancePair {
    IndexGroup indexGroup;
    float distance;
};

//Workgroup memory.
shared IndexDistancePair local_value[LOCAL_SIZE_X * 2];

uint getIndexOffset(uint i) {
    return indexOffset[i] / u_IndexOffsetStride;
}

uint getIndexLength(uint i) {
    return indexLength[i] / u_IndexLengthStride;
}

//If multiple y groups are used ignore the u_ChunkNum uniform and use gl_WorkGroupID.y instead
ChunkMultiDrawRange getSubInfo() {
    if(gl_NumWorkGroups.y == 1) {
        return chunkMultiDrawRange[u_ChunkNum];
    } else {
        return chunkMultiDrawRange[gl_WorkGroupID.y];
    }
}

vec4 unpackPos(Packed p) {
    uint x = p.a_Pos1 & uint(0xFFFF);
    uint y = (p.a_Pos1 >> 16);
    uint z = p.a_Pos2 & uint(0xFFFF);
    uint w = (p.a_Pos2 >> 16);
    return vec4(x,y,z,w);
}

float getAverageDistance(IndexGroup indexGroup) {
    if(indexGroup.i1 == DUMMY_INDEX) {
        return DUMMY_DISTANCE;
    }
    ChunkMultiDrawRange subInfo = getSubInfo();
    uint vOffset = vertexOffset[subInfo.DataOffset];

    vec4 rawPosition1 = unpackPos(region_mesh[indexGroup.i1 + vOffset]);
    vec4 rawPosition2 = unpackPos(region_mesh[indexGroup.i2 + vOffset]);
    vec4 rawPosition3 = unpackPos(region_mesh[indexGroup.i3 + vOffset]);
    vec4 rawPosition = (rawPosition1 + rawPosition2 + rawPosition3) / 3;

    vec3 vertexPosition = rawPosition.xyz * u_ModelScale + u_ModelOffset;
    vec3 chunkOffset = Chunks[int(rawPosition1.w)].Offset.xyz;
    vec4 pos = u_ModelViewMatrix * vec4(chunkOffset + vertexPosition, 1.0);

    return length(pos);
}

//Convert an index from [0..IndicesInChunk] to [0..IndicesInBuffer]
uint getFullIndex(uint index) {
    ChunkMultiDrawRange subInfo = getSubInfo();
    uint i = 0;
    while(i < subInfo.DataCount) {
        uint data = subInfo.DataOffset + i;
        if(index < getIndexLength(data)) {
            return getIndexOffset(data) + index;
        }
        index = index - getIndexLength(data);
        i = i + 1;
    }
    return DUMMY_INDEX;
}


// Performs compare-and-swap over elements held in shared, workgroup-local memory
void local_compare_and_swap(uvec2 idx){
    if (local_value[idx.x].distance < local_value[idx.y].distance) {
        IndexDistancePair tmp = local_value[idx.x];
        local_value[idx.x] = local_value[idx.y];
        local_value[idx.y] = tmp;
    }
}

// Performs full-height flip (h height) over locally available indices.
void local_flip(uint h){
    uint t = gl_LocalInvocationID.x;
    barrier();

    uint half_h = h >> 1; // Note: h >> 1 is equivalent to h / 2
    ivec2 indices =
    ivec2( h * ( ( 2 * t ) / h ) ) +
    ivec2( t % half_h, h - 1 - ( t % half_h ) );

    local_compare_and_swap(indices);
}

// Performs progressively diminishing disperse operations (starting with height h)
// on locally available indices: e.g. h==8 -> 8 : 4 : 2.
// One disperse operation for every time we can half h.
void local_disperse(in uint h){
    uint t = gl_LocalInvocationID.x;
    for ( ; h > 1 ; h /= 2 ) {

        barrier();

        uint half_h = h >> 1; // Note: h >> 1 is equivalent to h / 2
        ivec2 indices =
        ivec2( h * ( ( 2 * t ) / h ) ) +
        ivec2( t % half_h, half_h + ( t % half_h ) );

        local_compare_and_swap(indices);
    }
}

// Perform binary merge sort for local elements, up to a maximum number of elements h.
void local_bms(uint h){
    for (uint hh = 2; hh <= h; hh <<= 1) {  // note:  h <<= 1 is same as h *= 2
        local_flip(hh);
        local_disperse(hh/2);
    }
}



// Performs compare-and-swap over elements held in shared, workgroup-local memory
void global_compare_and_swap(uvec2 idx){
    uint i1 = getFullIndex(idx.x);
    uint i2 = getFullIndex(idx.y);
    float distance1 = getAverageDistance(region_index_groups[i1]);
    float distance2 = getAverageDistance(region_index_groups[i2]);

    if(i1 == DUMMY_INDEX || i2 == DUMMY_INDEX) {
        return;
    }

    if (distance1 < distance2) {
        IndexGroup tmp = region_index_groups[i1];
        region_index_groups[i1] = region_index_groups[i2];
        region_index_groups[i2] = tmp;
    }
}

// Performs full-height flip (h height) in buffer
void global_flip(uint h){
    uint t = gl_GlobalInvocationID.x;

    uint half_h = h >> 1;
    uint q = uint((2 * t) / h) * h;
    uint x = q + (t % half_h);
    uint y = q + h - (t % half_h) - 1;

    global_compare_and_swap(uvec2(x,y));
}

// Performs progressively diminishing disperse operations (starting with height h)
// One disperse operation for every time we can half h.
void global_disperse(uint h){
    uint t = gl_GlobalInvocationID.x;
    uint half_h = h >> 1;
    uint q = uint((2 * t) / h) * h;
    uint x = q + (t % half_h);
    uint y = q + (t % half_h) + half_h;
    global_compare_and_swap(uvec2(x,y));
}

void main(){

    if(u_ExecutionType == LOCAL_BMS || u_ExecutionType == LOCAL_DISPERSE) {
        uint t = gl_LocalInvocationID.x;
        uint offset = gl_WorkGroupSize.x * 2 * gl_WorkGroupID.x;

        uint fullIndex1 = getFullIndex(offset+t*2);
        uint fullIndex2 = getFullIndex(offset+t*2+1);
        IndexGroup rig1 = region_index_groups[fullIndex1];
        IndexGroup rig2 = region_index_groups[fullIndex2];
        float distance1 = getAverageDistance(rig1);
        float distance2 = getAverageDistance(rig2);

        if (fullIndex1 == DUMMY_INDEX) {
            rig1 = IndexGroup(DUMMY_INDEX, DUMMY_INDEX, DUMMY_INDEX);
            distance1 = DUMMY_DISTANCE;
        }
        if (fullIndex2 == DUMMY_INDEX) {
            rig2 = IndexGroup(DUMMY_INDEX, DUMMY_INDEX, DUMMY_INDEX);
            distance2 = DUMMY_DISTANCE;
        }

        // Each local worker must save two elements to local memory, as there
        // are twice as many elments as workers.
        local_value[t*2]   = IndexDistancePair(rig1, distance1);
        local_value[t*2+1] = IndexDistancePair(rig2, distance2);

        if (u_ExecutionType == LOCAL_BMS) {
            local_bms(u_SortHeight);
        }
        if (u_ExecutionType == LOCAL_DISPERSE) {
            local_disperse(u_SortHeight);
        }

        barrier();
        //Write local memory back to buffer
        IndexGroup ig1 = local_value[t*2].indexGroup;
        IndexGroup ig2 = local_value[t*2+1].indexGroup;

        if (fullIndex1 != DUMMY_INDEX) {
            region_index_groups[fullIndex1] = ig1;
        }
        if (fullIndex2 != DUMMY_INDEX) {
            region_index_groups[fullIndex2] = ig2;
        }
    }

    if(u_ExecutionType == GLOBAL_FLIP) {
        global_flip(u_SortHeight);
    }
    if(u_ExecutionType == GLOBAL_DISPERSE) {
        global_disperse(u_SortHeight);
    }
}