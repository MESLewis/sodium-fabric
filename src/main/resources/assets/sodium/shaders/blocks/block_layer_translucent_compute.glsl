#version 430

#define DUMMY_INDEX 10000000
#define DUMMY_DISTANCE -1000000

//TODO DEBUG
//TODO Calculate this based on GPU specs
#define LOCAL_SIZE_X 1024

// Externally defined via shader compiler.
//#ifndef LOCAL_SIZE_X
//#define LOCAL_SIZE_X 1
//#endif

// Note that there exist hardware limits -
// Look these up for your GPU via https://vulkan.gpuinfo.org/
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

vec4 unpackPos(Packed p) {
    uint x = p.a_Pos1 & uint(0xFFFF);
    uint y = (p.a_Pos1 >> 16);
    uint z = p.a_Pos2 & uint(0xFFFF);
    uint w = (p.a_Pos2 >> 16);
    return vec4(x,y,z,w);
}

float getDistance(uint index) {
    if(index == DUMMY_INDEX) {
        return DUMMY_DISTANCE;
    }
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
//    return min(getDistance(pair.i1), min(getDistance(pair.i2), getDistance(pair.i3)));
    return (getDistance(pair.i1)
    + getDistance(pair.i2)
    + getDistance(pair.i3)) / 3;
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

    //TODO is this messing with other groups if the index goes far enough out?
    //TODO I think I need to actually do the writes or the sort doesn't work.
    if(i1 == DUMMY_INDEX) {
        distance1 = DUMMY_DISTANCE;
    }

    if(i2 == DUMMY_INDEX) {
        distance2 = DUMMY_DISTANCE;
    }

    if (distance1 < distance2) {
        IndexGroup tmp = region_index_groups[i1];
        region_index_groups[i1] = region_index_groups[i2];
        region_index_groups[i2] = tmp;
    }
}

// Performs full-height flip (h height) in buffer
void global_flip(uint h, uint s){
    uint t = s * gl_WorkGroupSize.x + gl_LocalInvocationID.x;
    barrier();

    uint half_h = h / 2;
    ivec2 indices =
    ivec2( h * ( ( 2 * t ) / h ) ) +
    ivec2( t % half_h, h - 1 - ( t % half_h ) );

    global_compare_and_swap(indices);
}

// Performs progressively diminishing disperse operations (starting with height h)
// One disperse operation for every time we can half h.
void global_disperse(uint h, uint s){
    uint t = s * gl_WorkGroupSize.x + gl_LocalInvocationID.x;
    for ( ; h > 1 ; h /= 2 ) {

        barrier();

        uint half_h = h / 2;
        ivec2 indices =
        ivec2( h * ( ( 2 * t ) / h ) ) +
        ivec2( t % half_h, half_h + ( t % half_h ) );

        global_compare_and_swap(indices);
    }
}

// Perform binary merge sort for global elements, up to a maximum number of elements h.
void global_bms(uint n){
    //TODO figure out what to do for n > LOCAL_SIZE_X
    for (uint hh = 2; hh <= n; hh *= 2) {
        //Break it up into LOCAL_SIZE_X chunks
        for(uint ss = 1; ss <= n / LOCAL_SIZE_X; ss++) {
            global_flip(hh, ss);
            global_disperse(hh/2, ss);
            barrier();
        }
    }
}


void main(){
    uint t = gl_LocalInvocationID.x;
    uint indexLength = chunkMultiDrawRange[gl_WorkGroupID.x].DataIndexCount / u_IndexLengthStride;
    uint computeSize = uint(pow(2, ceil(log(indexLength / 2)/log(2))));

    uint fullIndex1 = getFullIndex(t*2);
    uint fullIndex2 = getFullIndex(t*2+1);
    IndexGroup rig1 = region_index_groups[fullIndex1];
    IndexGroup rig2 = region_index_groups[fullIndex2];
    float distance1 = getAverageDistance(rig1);
    float distance2 = getAverageDistance(rig2);

    if(fullIndex1 == DUMMY_INDEX) {
        rig1 = IndexGroup(0, 0, 0);
        distance1 = DUMMY_DISTANCE;
    }
    if(fullIndex2 == DUMMY_INDEX) {
        rig2 = IndexGroup(0, 0, 0);
        distance2 = DUMMY_DISTANCE;
    }


//    if(computeSize <= LOCAL_SIZE_X) {
        // Each local worker must save two elements to local memory, as there
        // are twice as many elments as workers.
//        local_value[t*2]   = IndexDistancePair(rig1, distance1);
//        local_value[t*2+1] = IndexDistancePair(rig2, distance2);
//    }


//    int n = parameters.n;
    int n = LOCAL_SIZE_X * 2;

//    local_bms(n);
    global_bms(computeSize);

//    barrier();

//    if(computeSize <= LOCAL_SIZE_X) {
//         Write local memory back to buffer
//        IndexGroup ig1 = local_value[t*2].indexGroup;
//        IndexGroup ig2 = local_value[t*2+1].indexGroup;
//
//        if (fullIndex1 != DUMMY_INDEX) {
//            region_index_groups[fullIndex1] = ig1;
//        }
//        if (fullIndex2 != DUMMY_INDEX) {
//            region_index_groups[fullIndex2] = ig2;
//        }
//    }
}



//void main() {
//    ChunkMultiDrawRange subInfo = chunkMultiDrawRange[gl_WorkGroupID.x];
//    //TODO DEBUG
//    uint max = min(subInfo.DataIndexCount / u_IndexLengthStride, 1000);
////    uint max = subInfo.DataIndexCount / u_IndexLengthStride;
//
//
//    //TODO Parallelize sort
//    //https://poniesandlight.co.uk/reflect/bitonic_merge_sort/
//
//    //Insertion sort of indicies based on vertex
//    int i = 1;
//
//    while(i < max) {
//        IndexGroup temp = region_index_groups[getFullIndex(i)];
//        float tempDist = getAverageDistance(temp);
//        int j = i - 1;
//        while(j >= 0 && getAverageDistance(region_index_groups[getFullIndex(j)]) < tempDist) {
//            region_index_groups[getFullIndex(j+1)] = region_index_groups[getFullIndex(j)];
//            j = j - 1;
//        }
//        region_index_groups[getFullIndex(j+1)] = temp;
//        i = i + 1;
//    }
//}