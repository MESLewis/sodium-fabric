package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferTarget;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferUsage;
import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformBlock;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.gl.util.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.PointerBuffer;

import java.nio.IntBuffer;
import java.util.ArrayList;

import static java.lang.Math.log;
import static java.lang.Math.pow;
import static net.minecraft.util.math.MathHelper.ceil;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL43C.*;

public class ComputeShaderInterface {

    private static final boolean TIMING = false;
    private static final int MEMORY_BARRIERS = GL_BUFFER_UPDATE_BARRIER_BIT | GL_UNIFORM_BARRIER_BIT;
    //1024 is the minimum. Some cards support 2048 but then we might run into memory issues
    private static final int computeWorkGroupSizeX = 1024;
    private final GlUniformMatrix4f uniformModelViewMatrix;
    private final GlUniformBlock uniformBlockDrawParameters;
    private final GlUniformFloat uniformModelScale;
    private final GlUniformFloat uniformModelOffset;
    private final GlUniformInt uniformExecutionType;
    private final GlUniformInt uniformSortHeight;
    private final ArrayList<Integer> pointerList = new ArrayList<>();
    private final ArrayList<Integer> subDataList = new ArrayList<>();

    private int[] queries = new int[2];
    private int currentQueryIndex = 0;
    private int[] times = new int[100];
    private int currentTimeIndex = 0;

    public ComputeShaderInterface(ShaderBindingContext context) {
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformModelScale = context.bindUniform("u_ModelScale", GlUniformFloat::new);
        this.uniformModelOffset = context.bindUniform("u_ModelOffset", GlUniformFloat::new);
        this.uniformExecutionType = context.bindUniform("u_ExecutionType", GlUniformInt::new);
        this.uniformSortHeight = context.bindUniform("u_SortHeight", GlUniformInt::new);

        this.uniformBlockDrawParameters = context.bindUniformBlock("ubo_DrawParameters", 0);

        if(TIMING) {
            glGenQueries(queries);
        }
    }

    public void setup(ChunkVertexType vertexType) {
        this.uniformModelScale.setFloat(vertexType.getModelScale());
        this.uniformModelOffset.setFloat(vertexType.getModelOffset());
    }

    /**
     * Executes the compute shader, using multiple calls to glDispatchCompute if
     * the data set is too large to be sorted in one call.
     */
    public boolean execute(CommandList commandList, MultiDrawBatch batch, RenderRegion.RenderRegionArenas arenas) {
        if(TIMING) {
            glBeginQuery(GL_TIME_ELAPSED, queries[currentQueryIndex]);
        }
        boolean isCheap = true;

        pointerList.clear();
        subDataList.clear();
        int chunkCount = 0;
        PointerBuffer pointerBuffer = batch.getPointerBuffer();
        IntBuffer countBuffer = batch.getCountBuffer();
        IntBuffer baseVertexBuffer = batch.getBaseVertexBuffer();

        int lastBaseVertexOffset = baseVertexBuffer.get(0);
        int subDataCount = 0;
        int totalSubDataCount = 0;
        int subDataIndexCount = 0;

        int pointer;
        int baseVertex;
        int count;
        int largestIndexCount = 0;
        while(countBuffer.hasRemaining()) {
            pointer = (int) (pointerBuffer.get());
            baseVertex = baseVertexBuffer.get();
            count = countBuffer.get();

            if(baseVertex != lastBaseVertexOffset) {
                lastBaseVertexOffset = baseVertex;

                subDataList.add(totalSubDataCount);
                subDataList.add(subDataCount);
                subDataList.add(subDataIndexCount);
                if(subDataIndexCount > largestIndexCount) {
                    largestIndexCount = subDataIndexCount;
                }
                chunkCount++;
                totalSubDataCount += subDataCount;
                subDataCount = 0;
                subDataIndexCount = 0;
            }
            pointerList.add(pointer); //IndexOffset
            subDataIndexCount += count;
            subDataCount++;
        }
        subDataList.add(totalSubDataCount);
        subDataList.add(subDataCount);
        subDataList.add(subDataIndexCount);
        if(subDataIndexCount > largestIndexCount) {
            largestIndexCount = subDataIndexCount;
        }
        chunkCount++;

        commandList.bindBufferBase(GlBufferTarget.SHADER_STORAGE_BUFFER, 1, arenas.vertexBuffers.getBufferObject());
        commandList.bindBufferBase(GlBufferTarget.SHADER_STORAGE_BUFFER, 2, arenas.indexBuffers.getBufferObject());

        GlMutableBuffer shaderBuffer;

        shaderBuffer = commandList.createMutableBuffer();
        commandList.bufferData(GlBufferTarget.SHADER_STORAGE_BUFFER, shaderBuffer, subDataList.stream().mapToInt(i -> i).toArray(), GlBufferUsage.DYNAMIC_DRAW);
        commandList.bindBufferBase(GlBufferTarget.SHADER_STORAGE_BUFFER, 3, shaderBuffer);

        shaderBuffer = commandList.createMutableBuffer();
        commandList.bufferData(GlBufferTarget.SHADER_STORAGE_BUFFER, shaderBuffer, pointerList.stream().mapToInt(i -> i).toArray(), GlBufferUsage.DYNAMIC_DRAW);
        commandList.bindBufferBase(GlBufferTarget.SHADER_STORAGE_BUFFER, 4, shaderBuffer);

        shaderBuffer = commandList.createMutableBuffer();
        commandList.bufferData(GlBufferTarget.SHADER_STORAGE_BUFFER, shaderBuffer, batch.getCountBuffer(), GlBufferUsage.DYNAMIC_DRAW);
        commandList.bindBufferBase(GlBufferTarget.SHADER_STORAGE_BUFFER, 5, shaderBuffer);

        shaderBuffer = commandList.createMutableBuffer();
        commandList.bufferData(GlBufferTarget.SHADER_STORAGE_BUFFER, shaderBuffer, batch.getBaseVertexBuffer(), GlBufferUsage.DYNAMIC_DRAW);
        commandList.bindBufferBase(GlBufferTarget.SHADER_STORAGE_BUFFER, 6, shaderBuffer);



        int LOCAL_BMS = 0;
        int LOCAL_DISPERSE = 1;
        int GLOBAL_FLIP = 2;
        int GLOBAL_DISPERSE = 3;


        int maxHeight = (int) pow(2, ceil(log(largestIndexCount / 3)/log(2)));
        int groups = (maxHeight / (computeWorkGroupSizeX * 2)) + 1;
        int height = computeWorkGroupSizeX * 2;

        //Begin by running a normal bitonic sort on all chunks.
        //For chunks whose translucent verticies are < maxComputeWorkGroupSizeX * 3 this
        //is the only work that needs to be done.
        uniformSortHeight.setInt(height);
        uniformExecutionType.setInt(LOCAL_BMS);
        glDispatchCompute(groups, chunkCount, 1);
        glMemoryBarrier(MEMORY_BARRIERS);

        height *= 2;

        //Keep getting height bigger until we cover all of n
        for(; height <= maxHeight; height *= 2) {
            isCheap = false;
            uniformExecutionType.set(GLOBAL_FLIP);
            uniformSortHeight.set(height);
            glDispatchCompute(groups, chunkCount, 1);
            glMemoryBarrier(MEMORY_BARRIERS);
            for(int halfHeight = height / 2; halfHeight > 1; halfHeight /= 2) {
                uniformSortHeight.set(halfHeight);
                if(halfHeight >= computeWorkGroupSizeX * 2)  {
                    uniformExecutionType.set(GLOBAL_DISPERSE);
                    glDispatchCompute(groups, chunkCount, 1);
                    glMemoryBarrier(MEMORY_BARRIERS);
                } else {
                    uniformExecutionType.setInt(LOCAL_DISPERSE);
                    glDispatchCompute(groups, chunkCount, 1);
                    glMemoryBarrier(MEMORY_BARRIERS);
                    break;
                }
            }
        }

        if(TIMING) {
            glEndQuery(GL_TIME_ELAPSED);
            currentQueryIndex = (currentQueryIndex + 1) % 2;
            //Query last frame's index so we don't slow down waiting for this frames query to finish
            times[currentTimeIndex] = glGetQueryObjecti(queries[currentQueryIndex], GL_QUERY_RESULT);
            currentTimeIndex = (currentTimeIndex + 1) % 100;
            if(currentTimeIndex == 0) {
                int totalTime = 0;
                for(int i : times) {
                    totalTime += i;
                }
                SodiumClientMod.logger().warn("Compute shader time: " + totalTime / times.length);
            }
        }
        return isCheap;
    }

    public void setModelViewMatrix(Matrix4f matrix) {
        this.uniformModelViewMatrix.set(matrix);
    }

    public void setDrawUniforms(GlMutableBuffer buffer) {
        this.uniformBlockDrawParameters.bindBuffer(buffer);
    }
}
