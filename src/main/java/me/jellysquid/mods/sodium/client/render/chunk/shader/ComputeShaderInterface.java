package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformBlock;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.gl.util.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL30C;

import java.nio.IntBuffer;
import java.util.ArrayList;

import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

public class ComputeShaderInterface {

    private static final boolean TIMING = true;
    //TODO are any memory barriers needed?
    private static final int MEMORY_BARRIERS = GL_BUFFER_UPDATE_BARRIER_BIT;
    private final GlUniformMatrix4f uniformModelViewMatrix;
    private final GlUniformBlock uniformBlockDrawParameters;
    private final GlUniformFloat uniformModelScale;
    private final GlUniformFloat uniformModelOffset;
    private final GlUniformInt uniformExecutionType;
    private final GlUniformInt uniformChunkNum;
    private final GlUniformInt uniformSortHeight;
    private final ArrayList<Integer> pointerList = new ArrayList<>();
    private final ArrayList<Integer> subDataList = new ArrayList<>();
    private final int maxComptuteWorkGroupSizeX;

    private int[] queries = new int[2];
    private int currentQueryIndex = 0;
    private int[] times = new int[100];
    private int currentTimeIndex = 0;

    public ComputeShaderInterface(ShaderBindingContext context) {
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformModelScale = context.bindUniform("u_ModelScale", GlUniformFloat::new);
        this.uniformModelOffset = context.bindUniform("u_ModelOffset", GlUniformFloat::new);
        this.uniformExecutionType = context.bindUniform("u_ExecutionType", GlUniformInt::new);
        this.uniformChunkNum = context.bindUniform("u_ChunkNum", GlUniformInt::new);
        this.uniformSortHeight = context.bindUniform("u_SortHeight", GlUniformInt::new);

        this.uniformBlockDrawParameters = context.bindUniformBlock("ubo_DrawParameters", 0);


        int[] maxComputeWorkGroupSize = new int[3];
        GL30C.glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, maxComputeWorkGroupSize);
        maxComptuteWorkGroupSizeX = maxComputeWorkGroupSize[0];

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
    public void execute(MultiDrawBatch batch, RenderRegion.RenderRegionArenas arenas) {
        if(TIMING) {
            glBeginQuery(GL_TIME_ELAPSED, queries[currentQueryIndex]);
        }

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
        while(countBuffer.hasRemaining()) {
            pointer = (int) (pointerBuffer.get());
            baseVertex = baseVertexBuffer.get();
            count = countBuffer.get();

            if(baseVertex != lastBaseVertexOffset) {
                lastBaseVertexOffset = baseVertex;

                subDataList.add(totalSubDataCount);
                subDataList.add(subDataCount);
                subDataList.add(subDataIndexCount);
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
        chunkCount++;

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, arenas.vertexBuffers.getBufferObject().handle());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, arenas.indexBuffers.getBufferObject().handle());

        int[] buf = new int[4];
        glGenBuffers(buf);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[0]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, subDataList.stream().mapToInt(i -> i).toArray(), GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, buf[0]);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[1]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, pointerList.stream().mapToInt(i -> i).toArray(), GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, buf[1]);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[2]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, batch.getCountBuffer(), GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, buf[2]);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[3]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, batch.getBaseVertexBuffer(), GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, buf[3]);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);


        int LOCAL_BMS = 0;
        int LOCAL_DISPERSE = 1;
        int GLOBAL_FLIP = 2;
        int GLOBAL_DISPERSE = 3;

        int height = maxComptuteWorkGroupSizeX * 2;
        //Begin by running a normal bitonic sort on all chunks.
        //For chunks whose translucent verticies are < maxComputeWorkGroupSizeX * 3 this
        //is the only work that needs to be done.
        uniformExecutionType.setInt(LOCAL_BMS);
        uniformSortHeight.setInt(height);
        glDispatchCompute(1, chunkCount, 1);
        glMemoryBarrier(MEMORY_BARRIERS);

        //TODO More batching can be done here, unsure how often a real world hits this branch
        for(int i = 0; i < chunkCount; i++) {
            uniformChunkNum.setInt(i);
            int n = subDataList.get(i*3+2) / 3; //subDataList has indicy count but we want tri count
            int groups = (n / (maxComptuteWorkGroupSizeX * 2)) + 1;
            height = maxComptuteWorkGroupSizeX * 2;

            if(groups > 1) {
                glDispatchCompute(groups, 1, 1);
                glMemoryBarrier(MEMORY_BARRIERS);
            }

            height *= 2;

            //Keep getting height bigger until we cover all of n
            for(; height <= n; height *= 2) {
                uniformExecutionType.set(GLOBAL_FLIP);
                uniformSortHeight.set(height);
                glDispatchCompute(groups, 1, 1);
                glMemoryBarrier(MEMORY_BARRIERS);
                for(int halfHeight = height / 2; halfHeight > 1; halfHeight /= 2) {
                    uniformSortHeight.set(halfHeight);
                    if(halfHeight >= maxComptuteWorkGroupSizeX * 2)  {
                        uniformExecutionType.set(GLOBAL_DISPERSE);
                        glDispatchCompute(groups, 1, 1);
                        glMemoryBarrier(MEMORY_BARRIERS);
                    } else {
                        uniformExecutionType.setInt(LOCAL_DISPERSE);
                        glDispatchCompute(groups, 1, 1);
                        glMemoryBarrier(MEMORY_BARRIERS);
                        break;
                    }
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
    }

    public void setModelViewMatrix(Matrix4f matrix) {
        this.uniformModelViewMatrix.set(matrix);
    }

    public void setDrawUniforms(GlMutableBuffer buffer) {
        this.uniformBlockDrawParameters.bindBuffer(buffer);
    }
}
