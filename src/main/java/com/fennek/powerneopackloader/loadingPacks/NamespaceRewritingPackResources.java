package com.fennek.powerneopackloader.loadingPacks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class NamespaceRewritingPackResources extends AbstractPackResources {
    private final PackResources delegate;
    private final String realNamespace;   // "createaeronauticscarworks"
    private final String virtualNamespace; // this pack's id

    public NamespaceRewritingPackResources(PackLocationInfo info, PackResources delegate,
                                           String realNamespace, String virtualNamespace) {
        super(info);
        this.delegate = delegate;
        this.realNamespace = realNamespace;
        this.virtualNamespace = virtualNamespace;
    }

    private ResourceLocation toReal(ResourceLocation virtual) {
        return virtual.getNamespace().equals(virtualNamespace)
                ? ResourceLocation.fromNamespaceAndPath(realNamespace, virtual.getPath())
                : virtual;
    }

    @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (location.getNamespace().equals(virtualNamespace)) {
            // Prefer content the pack already authored directly under its own id (the current
            // convention: data/<packId>/..., and now assets/<packId>/... too) - no rewriting
            // needed, it's already exactly where it claims to be.
            IoSupplier<InputStream> direct = delegate.getResource(type, location);
            if (direct != null) return direct;

            // Fall back to legacy content authored under the shared mod namespace, remapped.
            IoSupplier<InputStream> remapped = delegate.getResource(type, toReal(location));
            return rewriteModelReferences(type, location, remapped);
        }

        if (location.getNamespace().equals(realNamespace)) {
            // Only reachable through the virtual namespace above - never expose the shared mod
            // namespace itself, or two packs' legacy content would collide again.
            return null;
        }

        // Any other namespace this pack happens to contain - pass through untouched.
        return delegate.getResource(type, location);
    }

    private IoSupplier<InputStream> rewriteModelReferences(PackType type, ResourceLocation location,
                                                            IoSupplier<InputStream> resource) {
        if (resource == null || !isModelJson(type, location)) return resource;

        // A legacy CACW pack stores its files below assets/createaeronauticscarworks,
        // but it is exposed to Minecraft using the pack's own id.  Model JSON contains
        // absolute texture/parent references, so those must use that same virtual
        // namespace or Minecraft looks up a texture which this pack intentionally hides.
        return () -> {
            try (InputStream input = resource.get()) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                String rewritten = json.replace(
                        "\"" + realNamespace + ":",
                        "\"" + virtualNamespace + ":");
                return new ByteArrayInputStream(rewritten.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private boolean isModelJson(PackType type, ResourceLocation location) {
        return type == PackType.CLIENT_RESOURCES
                && location.getNamespace().equals(virtualNamespace)
                && location.getPath().startsWith("models/")
                && location.getPath().endsWith(".json");
    }

    @Override public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (namespace.equals(virtualNamespace)) {
            // Content already sitting directly under the pack's own id.
            delegate.listResources(type, virtualNamespace, path, output);
            // Plus legacy content under the shared mod namespace, remapped.
            delegate.listResources(type, realNamespace, path, (real, supplier) -> {
                ResourceLocation virtual = ResourceLocation.fromNamespaceAndPath(virtualNamespace, real.getPath());
                // ModelBakery obtains discovered model files from this supplier directly,
                // bypassing getResource. Apply the same in-memory reference translation here.
                output.accept(virtual, rewriteModelReferences(type, virtual, supplier));
            });
            return;
        }

        if (namespace.equals(realNamespace)) return; // hidden - only reachable remapped, above

        delegate.listResources(type, namespace, path, output); // any other namespace: untouched
    }

    @Override public Set<String> getNamespaces(PackType type) {
        Set<String> raw = delegate.getNamespaces(type);
        Set<String> result = new java.util.HashSet<>(raw);
        result.remove(realNamespace); // never expose the shared mod namespace directly
        if (raw.contains(realNamespace)) {
            result.add(virtualNamespace);
        }
        return result;
    }

    @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> s) throws IOException {
        return delegate.getMetadataSection(s);
    }
    @Override public IoSupplier<InputStream> getRootResource(String... paths) { return delegate.getRootResource(paths); }
    @Override public void close() { delegate.close(); }
}
