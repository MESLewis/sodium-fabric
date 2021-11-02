package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.*;
import me.jellysquid.mods.sodium.client.gl.util.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.PointerBuffer;

import java.nio.IntBuffer;
import java.util.ArrayList;

import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

public class ComputeShaderInterface {

    private final GlUniformMatrix4f uniformModelViewMatrix;
    private final GlUniformBlock uniformBlockDrawParameters;
    private final GlUniformFloat uniformModelScale;
    private final GlUniformFloat uniformModelOffset;
    private final ArrayList<Integer> multiDrawList = new ArrayList<>();
    private final ArrayList<Integer> subDataList = new ArrayList<>();

    public ComputeShaderInterface(ShaderBindingContext context) {
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformModelScale = context.bindUniform("u_ModelScale", GlUniformFloat::new);
        this.uniformModelOffset = context.bindUniform("u_ModelOffset", GlUniformFloat::new);

        this.uniformBlockDrawParameters = context.bindUniformBlock("ubo_DrawParameters", 0);
    }

    public void setup(ChunkVertexType vertexType) {
        this.uniformModelScale.setFloat(vertexType.getModelScale());
        this.uniformModelOffset.setFloat(vertexType.getModelOffset());
    }

    public void execute(MultiDrawBatch batch, RenderRegion.RenderRegionArenas arenas) {
        multiDrawList.clear();
        subDataList.clear();
        int chunkCount = 0;
        PointerBuffer pointerBuffer = batch.getPointerBuffer();
        IntBuffer countBuffer = batch.getCountBuffer();
        IntBuffer baseVertexBuffer = batch.getBaseVertexBuffer();

        int lastBaseVertexOffset = baseVertexBuffer.get(0);
        int subDataCount = 0;
        int totalSubDataCount = 0;

        int pointer;
        int count;
        int baseVertex;
        while(countBuffer.hasRemaining()) {
            pointer = (int) (pointerBuffer.get());
            count = countBuffer.get();
            baseVertex = baseVertexBuffer.get();

            multiDrawList.add(pointer); //IndexOffset
            multiDrawList.add(count); //IndexLength
            multiDrawList.add(baseVertex); //VertexOffset

            if(baseVertex != lastBaseVertexOffset) {
                lastBaseVertexOffset = baseVertex;

                subDataList.add(totalSubDataCount);
                subDataList.add(subDataCount);
                totalSubDataCount += subDataCount;
                subDataCount = 0;
                chunkCount++;
            }
            subDataCount++;
        }
        subDataList.add(totalSubDataCount);
        subDataList.add(subDataCount);
        chunkCount++;

        int buf = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf);
        glBufferData(GL_SHADER_STORAGE_BUFFER, multiDrawList.stream().mapToInt(i -> i).toArray(), GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, buf);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        buf = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf);
        glBufferData(GL_SHADER_STORAGE_BUFFER, subDataList.stream().mapToInt(i -> i).toArray(), GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, buf);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, arenas.vertexBuffers.getBufferObject().handle());
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, arenas.indexBuffers.getBufferObject().handle());
        glDispatchCompute(chunkCount, 1, 1);
    }

    public void setModelViewMatrix(Matrix4f matrix) {
        this.uniformModelViewMatrix.set(matrix);
    }

    public void setDrawUniforms(GlMutableBuffer buffer) {
        this.uniformBlockDrawParameters.bindBuffer(buffer);
    }
}
