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
    private final ArrayList<Integer> pointerList = new ArrayList<>();
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
        pointerList.clear();
        subDataList.clear();
        int chunkCount = 0;
        PointerBuffer pointerBuffer = batch.getPointerBuffer();
        IntBuffer baseVertexBuffer = batch.getBaseVertexBuffer();

        int lastBaseVertexOffset = baseVertexBuffer.get(0);
        int subDataCount = 0;
        int totalSubDataCount = 0;

        int pointer;
        int baseVertex;
        while(pointerBuffer.hasRemaining()) {
            pointer = (int) (pointerBuffer.get());
            baseVertex = baseVertexBuffer.get();

            pointerList.add(pointer); //IndexOffset

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
