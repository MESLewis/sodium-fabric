#version 430

layout(local_size_x = 1, local_size_y = 1) in;

//in uvec3 gl_NumWorkGroups;
//in uvec3 gl_workGroupID;
//in uvec3 gl_LocalInvocationID;
//in uvec3 gl_GlobalInvocationID;
//in uint  gl_LocalInvocationIndex;

struct DrawParameters {
// Older AMD drivers can't handle vec3 in std140 layouts correctly
// The alignment requirement is 16 bytes (4 float components) anyways, so we're not wasting extra memory with this,
// only fixing broken drivers.
    vec4 Offset;
};


//TODO make all these struct and var names not suck

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
};

uniform mat4 u_ModelViewMatrix;
uniform float u_ModelScale;
uniform float u_ModelOffset;
uniform uint u_IndexOffsetStride = 12; //Number of bits referenced per array entry
uniform uint u_IndexLengthStride = 3; //Number of vertices referenced per array entry

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


uint getIndexOffset(uint i) {
    return indexOffset[i] / u_IndexOffsetStride;
}

uint getIndexLength(uint i) {
    return indexLength[i] / u_IndexLengthStride;
}

vec4 unpackPos(Packed p) {
    uint x = p.a_Pos1 & uint(0xFFFF);
    uint y = (p.a_Pos1 >> 16);
    uint z = p.a_Pos2 & uint(0xFFFF);
    uint w = (p.a_Pos2 >> 16);
    return vec4(x,y,z,w);
}

float getDistance(uint index) {
    ChunkMultiDrawRange subInfo = chunkMultiDrawRange[gl_WorkGroupID.x];
    uint vOffset = vertexOffset[subInfo.DataOffset];

    vec4 rawPosition = unpackPos(region_mesh[index + vOffset]);

    vec3 vertexPosition = rawPosition.xyz * u_ModelScale + u_ModelOffset;
    vec3 chunkOffset = Chunks[int(rawPosition.w)].Offset.xyz;
    vec4 pos = u_ModelViewMatrix * vec4(chunkOffset + vertexPosition, 1.0);

    return length(pos);
}

float getAverageDistance(IndexGroup pair) {
    //TODO Find best heuristic
    return min(getDistance(pair.i1), min(getDistance(pair.i2), getDistance(pair.i3)));
//    return getDistance(pair.i1)
//    + getDistance(pair.i2)
//    + getDistance(pair.i3);
}

//Convert an index from [0..IndicesInChunk] to [0..IndicesInBuffer]
uint getFullIndex(uint index) {
    ChunkMultiDrawRange subInfo = chunkMultiDrawRange[gl_WorkGroupID.x];
    uint i = 0;
    while(i < subInfo.DataCount) {
        uint data = subInfo.DataOffset + i;
        if(index < getIndexLength(data)) {
            return getIndexOffset(data) + index;
        }
        index = index - getIndexLength(data);
        i = i + 1;
    }
    return index;
}

uint getIndexCount() {
    ChunkMultiDrawRange subInfo = chunkMultiDrawRange[gl_WorkGroupID.x];
    uint r = 0;
    for(uint i = subInfo.DataOffset; i < subInfo.DataOffset + subInfo.DataCount; i = i + 1) {
        r = r + getIndexLength(i);
    }
    return r;
}

void main() {
    //Insertion sort of indicies based on vertex
    int i = 1;
    uint max = getIndexCount();

    while(i < max) {
        IndexGroup temp = region_index_groups[getFullIndex(i)];
        float tempDist = getAverageDistance(temp);
        int j = i - 1;
        while(j >= 0 && getAverageDistance(region_index_groups[getFullIndex(j)]) < tempDist) {
            region_index_groups[getFullIndex(j+1)] = region_index_groups[getFullIndex(j)];
            j = j - 1;
        }
        region_index_groups[getFullIndex(j+1)] = temp;
        i = i + 1;
    }
}