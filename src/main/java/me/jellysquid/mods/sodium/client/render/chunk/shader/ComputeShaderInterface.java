package me.jellysquid.mods.sodium.client.render.chunk.shader;

import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.*;
import net.minecraft.util.math.Matrix4f;

public class ComputeShaderInterface {

    private final GlUniformMatrix4f uniformModelViewMatrix;
    private final GlUniformBlock uniformBlockDrawParameters;
    public final GlUniformFloat uniformModelScale;
    public final GlUniformFloat uniformModelOffset;
//    public final GlUniformBlock batchSubData;
//    public final GlUniformInt uniformIndexOffset;
//    public final GlUniformInt uniformIndexLength;
//    public final GlUniformInt uniformVertexOffset;

    public ComputeShaderInterface(ShaderBindingContext context) {
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformModelScale = context.bindUniform("u_ModelScale", GlUniformFloat::new);
        this.uniformModelOffset = context.bindUniform("u_ModelOffset", GlUniformFloat::new);
//        this.uniformIndexOffset = context.bindUniform("u_IndexOffset", GlUniformInt::new);
//        this.uniformIndexLength = context.bindUniform("u_IndexLength", GlUniformInt::new);
//        this.uniformVertexOffset = context.bindUniform("u_VertexOffset", GlUniformInt::new);

        this.uniformBlockDrawParameters = context.bindUniformBlock("ubo_DrawParameters", 0);
//        this.batchSubData = context.bindUniformBlock("sub_buffer", 3);
    }


    public void setModelViewMatrix(Matrix4f matrix) {
        this.uniformModelViewMatrix.set(matrix);
    }

    public void setDrawUniforms(GlMutableBuffer buffer) {
        this.uniformBlockDrawParameters.bindBuffer(buffer);
    }
}
