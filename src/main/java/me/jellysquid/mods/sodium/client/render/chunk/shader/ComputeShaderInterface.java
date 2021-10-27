package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformBlock;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3f;

import static org.lwjgl.opengl.GL43C.glDispatchCompute;

public class ComputeShaderInterface {

    private final GlUniformMatrix4f uniformModelViewMatrix;
    private final GlUniformBlock uniformBlockDrawParameters;
    public final GlUniformFloat uniformModelScale;
    public final GlUniformFloat uniformModelOffset;

    public ComputeShaderInterface(ShaderBindingContext context) {
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformModelScale = context.bindUniform("u_ModelScale", GlUniformFloat::new);
        this.uniformModelOffset = context.bindUniform("u_ModelOffset", GlUniformFloat::new);

        this.uniformBlockDrawParameters = context.bindUniformBlock("ubo_DrawParameters", 0);
    }


    public void setup(ChunkVertexType vertexType) {
//        getChunkShaderCompute().bind();
    }

    public void run() {
    }

    public void setModelViewMatrix(Matrix4f matrix) {
        this.uniformModelViewMatrix.set(matrix);
    }

    public void setProjectionMatrix(Matrix4f matrix) {
//        this.uniformProjectionMatrix.set(matrix);
    }

    public void setCameraPos(float x, float y, float z) {
//       this.uniformCameraPos.set(new float[]{x, y, z, 1.0f});
    }

    public void setDrawUniforms(GlMutableBuffer buffer) {
        this.uniformBlockDrawParameters.bindBuffer(buffer);
    }
}
