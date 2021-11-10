package me.jellysquid.mods.sodium.client.render.chunk;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.*;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.chunk.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.shader.*;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL30C;

import java.util.Map;

import static org.lwjgl.opengl.GL43C.GL_MAX_COMPUTE_WORK_GROUP_SIZE;

public abstract class ShaderChunkRenderer implements ChunkRenderer {
    //Variable names that are hard coded in shader language.
    private final static String SHADER_VAR_LOCAL_SIZE_X = "LOCAL_SIZE_X";
    private final Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> programs = new Object2ObjectOpenHashMap<>();
    private final Map<ChunkShaderOptions, GlProgram<ComputeShaderInterface>> computes = new Object2ObjectOpenHashMap<>();

    protected final ChunkVertexType vertexType;
    protected final GlVertexFormat<ChunkMeshAttribute> vertexFormat;

    protected final RenderDevice device;

    protected GlProgram<ChunkShaderInterface> activeProgram;

    public ShaderChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        this.device = device;
        this.vertexType = vertexType;
        this.vertexFormat = vertexType.getCustomVertexFormat();
    }

    protected GlProgram<ChunkShaderInterface> compileProgram(ChunkShaderOptions options) {
        GlProgram<ChunkShaderInterface> program = this.programs.get(options);

        if (program == null) {
            this.programs.put(options, program = this.createShader("blocks/block_layer_opaque", options));
        }

        if (options.pass().isTranslucent() && SodiumClientMod.options().advanced.useTranslucentFaceSorting) {
            GlProgram<ComputeShaderInterface> compute = this.computes.get(options);

            if (compute == null) {
                //TODO verify various numbers to calculate best work group size
                ShaderConstants.Builder constantBuilder = ShaderConstants.builder();

                int[] maxComputeWorkGroupSize = new int[3];
                GL30C.glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, maxComputeWorkGroupSize);
                constantBuilder.add(SHADER_VAR_LOCAL_SIZE_X, "" + maxComputeWorkGroupSize[0]);

                //Translucent passes use a compute shader for sorting
                GlShader shader = ShaderLoader.loadShader(ShaderType.COMPUTE,
                        new Identifier("sodium", "blocks/block_layer_translucent_compute.glsl"), constantBuilder.build());

                try {
                    this.computes.put(options,
                            compute = GlProgram.builder(new Identifier("sodium", "chunk_shader_compute"))
                                    .attachShader(shader)
                                    .link(ComputeShaderInterface::new));
                } finally {
                    shader.delete();
                }
            }
            program.getInterface().setCompute(compute);
        }

        return program;
    }

    private GlProgram<ChunkShaderInterface> createShader(String path, ChunkShaderOptions options) {
        ShaderConstants constants = options.constants();

        GlShader vertShader = ShaderLoader.loadShader(ShaderType.VERTEX,
                new Identifier("sodium", path + ".vsh"), constants);

        GlShader fragShader = ShaderLoader.loadShader(ShaderType.FRAGMENT,
                new Identifier("sodium", path + ".fsh"), constants);

        try {
            return GlProgram.builder(new Identifier("sodium", "chunk_shader"))
                    .attachShader(vertShader)
                    .attachShader(fragShader)
                    .bindAttribute("a_Pos", ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE)
                    .bindAttribute("a_LightCoord", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE)
                    .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                    .link((shader) -> new ChunkShaderInterface(shader, options));
        } finally {
            vertShader.delete();
            fragShader.delete();
        }
    }

    protected void begin(BlockRenderPass pass) {
        ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, pass);

        this.activeProgram = this.compileProgram(options);
        this.activeProgram.bind();
        this.activeProgram.getInterface()
                .setup(this.vertexType);
    }

    protected void end() {
        this.activeProgram.unbind();
        this.activeProgram = null;
    }

    @Override
    public void delete() {
        this.programs.values()
                .forEach(GlProgram::delete);
        this.programs.clear();
    }

    @Override
    public ChunkVertexType getVertexType() {
        return this.vertexType;
    }
}
