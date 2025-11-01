package org.example;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNull;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class Optimizer {

    // Helper records for managing the optimization process
    /** Represents a single face's texture area, extracted from the original texture. */
    private record TextureArea(
            Key modelKey,
            Model.Element element,
            Model.Element.Face face,
            BufferedImage imagePatch,
            boolean originalFlipH,
            boolean originalFlipV
    ) {}

    /** Represents a deduplicated, canonical texture area and all the faces that match it. */
    private static class UniqueTextureArea {
        final BufferedImage canonicalImage;
        final int width, height;
        final List<Match> matches = new ArrayList<>();
        int newX, newY; // Where it will be placed in the new atlas

        // A "match" records which face maps to this area and what transform is needed
        record Match(TextureArea area, Transform transform) {}

        UniqueTextureArea(BufferedImage canonicalImage, TextureArea firstArea, Transform transform) {
            this.canonicalImage = canonicalImage;
            this.width = canonicalImage.getWidth();
            this.height = canonicalImage.getHeight();
            addMatch(firstArea, transform);
        }

        void addMatch(TextureArea area, Transform transform) {
            this.matches.add(new Match(area, transform));
        }

        void setNewCoordinates(int x, int y) {
            this.newX = x;
            this.newY = y;
        }
    }

    /** Represents the final packed texture atlas. */
    private record PackedTexture(
            Key newTextureKey,
            BufferedImage newAtlas,
            List<UniqueTextureArea> packedAreas
    ) {}

    /** Represents possible transformations for deduplication. */
    private enum Transform { NONE, FLIP_H, FLIP_V, FLIP_HV }


    // Class members
    private final ResourcePack resourcePack;
    private final Path outputPackPath;
    private final Map<Key, Model> modelCache = new HashMap<>();

    public Optimizer(ResourcePack resourcePack, Path outputPackPath) {
        this.resourcePack = resourcePack;
        this.outputPackPath = outputPackPath;
    }

    /**
     * Main entry point to run the optimization.
     */
    public void optimize() throws IOException {
        System.out.println("Starting optimization...");

        // Steps 1 & 2: Find models and group them by texture
        Map<Key, Set<Key>> textureToModels = findModelsToOptimize();
        System.out.println("Found " + textureToModels.size() + " texture groups to optimize.");

        int c = 0;

        for (Map.Entry<Key, Set<Key>> entry : textureToModels.entrySet()) {
            Key originalTextureKey = entry.getKey();
            Set<Key> modelKeys = entry.getValue();

            System.out.println("Processing texture: " + originalTextureKey + " (" + modelKeys.size() + " models)");

            try {
                var success = processTextureGroup(originalTextureKey, modelKeys);
                if (success) c++;
            } catch (Exception e) {
                System.err.println("Failed to process group for texture " + originalTextureKey + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.println("Optimized " + c + " textures");
        System.out.println("Optimization complete. Output at: " + outputPackPath);
    }

    /**
     * Implements Steps 1 & 2:
     * 1. Go through all models (item models) with no parent and only 1 texture.
     * 2. Group models that share the same texture.
     */
    private Map<Key, Set<Key>> findModelsToOptimize() throws IOException {
        List<Key> allModels = resourcePack.discoverAllModels();
        Map<Key, Set<Key>> textureToModels = new HashMap<>();

        for (Key modelKey : allModels) {
            Model model = resourcePack.getModel(modelKey);

            // 1. Check criteria
            if (model == null || model.parent != null) continue;
            if (model.elements == null || model.elements.isEmpty()) continue;
            if (model.textures == null) continue;

            modelCache.put(modelKey, model); // Cache for later use

            int textureCount = model.getTextures().size();
            if (textureCount == 0) continue;

            var texKey = model.getTextures().iterator().next();
            var tex = resourcePack.hasTexture(texKey);
            if (tex) continue;

            textureToModels.computeIfAbsent(model.getTextures().iterator().next(), k -> new HashSet<>()).add(modelKey);
        }

        // Filter out groups with only one model
        //textureToModels.entrySet().removeIf(entry -> entry.getValue().size() < 2);

        return textureToModels;
    }

    /**
     * Implements Steps 3-6 for a single texture group.
     */
    private boolean processTextureGroup(Key textureKey, Set<Key> modelKeys) throws IOException {
        Texture originalTexture = resourcePack.getTexture(textureKey);

        // Step 3: Copy all used texture areas from all faces
        List<TextureArea> allAreas = extractAllTextureAreas(modelKeys, originalTexture);
        if (allAreas.isEmpty()) {
            System.out.println("  ...No valid texture areas found, skipping.");
            return false;
        }

        // Step 4: Deduplicate texture areas (checking for flips)
        List<UniqueTextureArea> uniqueAreas = deduplicateAreas(allAreas);
        System.out.println("  ...Original areas: " + allAreas.size() + ", Unique areas: " + uniqueAreas.size());

        // Step 5: Repack the unique areas into a new texture atlas
        PackedTexture packedResult = packTexture(uniqueAreas, textureKey);
        //resourcePack.saveTexture(packedResult.newTextureKey(), packedResult.newAtlas(), outputPackPath);

        int origW = originalTexture.image().getWidth();
        int origH = originalTexture.image().getHeight();
        int newW = packedResult.newAtlas().getWidth();
        int newH = packedResult.newAtlas().getHeight();

        System.out.println("Before " + origW + "x" + origH);
        System.out.println("After  " + newW + "x" + newH);

        // Only save if the new texture is smaller in *any* dimension
        if (newW < origW || newH < origH) {
            resourcePack.saveTexture(textureKey, packedResult.newAtlas(), outputPackPath);
            System.out.println("Saved optimized texture (smaller dimensions).");

            // Step 6: Patch up the UVs in the item models
            patchModels(modelKeys, packedResult, textureKey);

            return true;
        } else {
            System.out.println("Skipped save — new texture not smaller.");
        }

        return false;
    }

    /**
     * Step 3: Copy all used texture areas from all faces
     */
    private List<TextureArea> extractAllTextureAreas(Set<Key> modelKeys, Texture originalTexture) {
        List<TextureArea> allAreas = new ArrayList<>();
        BufferedImage originalImage = originalTexture.image();
        int imgW = originalImage.getWidth();
        int imgH = originalImage.getHeight();

        for (Key modelKey : modelKeys) {
            Model model = modelCache.get(modelKey); // Get from cache
            if (model == null || model.elements == null) continue;

            // Find the texture variable (e.g., "#0" or "#layer0")
            String textureVar = null;
            for(var entry : model.textures.entrySet()) {
                if (!entry.getKey().equals("particle") && entry.getValue().isKey()) {
                    textureVar = "#" + entry.getKey();
                    break;
                }
            }
            if (textureVar == null) continue;

            for (Model.Element element : model.elements) {
                if (element.faces == null) continue;
                for (Model.Element.Face face : element.faces.values()) {
                    // Check if this face uses the texture we're optimizing
                    if (face.texture != null && face.texture.equals(textureVar) && face.uv != null) {
                        try {
                            float[] uv = face.uv;
                            double x1_p = (uv[0] / 16.0) * imgW;
                            double y1_p = (uv[1] / 16.0) * imgH;
                            double x2_p = (uv[2] / 16.0) * imgW;
                            double y2_p = (uv[3] / 16.0) * imgH;

                            // Get pixel-perfect bounding box
                            int x = (int) Math.round(Math.min(x1_p, x2_p));
                            int y = (int) Math.round(Math.min(y1_p, y2_p));
                            int w = (int) Math.round(Math.abs(x2_p - x1_p));
                            int h = (int) Math.round(Math.abs(y2_p - y1_p));

                            if (w <= 0 || h <= 0) continue; // Skip degenerate faces
                            if (x + w > imgW || y + h > imgH) continue; // Skip out-of-bounds UVs

                            BufferedImage patch = originalImage.getSubimage(x, y, w, h);

                            // Store the original flip state, this is CRITICAL for Step 6
                            boolean originalFlipH = uv[0] > uv[2];
                            boolean originalFlipV = uv[1] > uv[3];

                            allAreas.add(new TextureArea(modelKey, element, face, patch, originalFlipH, originalFlipV));
                        } catch (Exception e) {
                            System.err.println("  ...Skipping face due to error: " + e.getMessage() + " on " + modelKey);
                        }
                    }
                }
            }
        }
        return allAreas;
    }

    /**
     * Step 4: Deduplicate texture areas, checking for flips.
     */
    private List<UniqueTextureArea> deduplicateAreas(List<TextureArea> allAreas) {
        List<UniqueTextureArea> uniqueList = new ArrayList<>();

        for (TextureArea area : allAreas) {
            // "Render" the patch by applying its original flip
            BufferedImage renderedPatch = flip(area.imagePatch(), area.originalFlipH(), area.originalFlipV());

            UniqueTextureArea match = null;
            Transform matchTransform = Transform.NONE;

            for (UniqueTextureArea unique : uniqueList) {
                // Compare rendered patch against the canonical image and its transformations
                if (areImagesEqual(renderedPatch, unique.canonicalImage)) {
                    match = unique;
                    matchTransform = Transform.NONE;
                    break;
                }
                if (areImagesEqual(renderedPatch, flip(unique.canonicalImage, true, false))) {
                    match = unique;
                    matchTransform = Transform.FLIP_H;
                    break;
                }
                if (areImagesEqual(renderedPatch, flip(unique.canonicalImage, false, true))) {
                    match = unique;
                    matchTransform = Transform.FLIP_V;
                    break;
                }
                if (areImagesEqual(renderedPatch, flip(unique.canonicalImage, true, true))) {
                    match = unique;
                    matchTransform = Transform.FLIP_HV;
                    break;
                }
            }

            if (match != null) {
                // Found a match. Record which transform makes this patch equal the canonical.
                match.addMatch(area, matchTransform);
            } else {
                // No match. This is a new unique (canonical) area.
                // The canonical image is the *rendered* patch.
                // The transform to get from its *own* rendered patch to canonical is NONE.
                uniqueList.add(new UniqueTextureArea(renderedPatch, area, Transform.NONE));
            }
        }
        return uniqueList;
    }

    /**
     * Step 5: Repack the texture (using a simple shelf-packing algorithm).
     */
    private PackedTexture packTexture(List<UniqueTextureArea> areas, Key originalKey) {
        // Sort by height, descending. Good heuristic for shelf packing.
        areas.sort((a, b) -> Integer.compare(b.height, a.height));

        int newWidth = 16;
        int newHeight = 16;
        BufferedImage newAtlas = null;
        boolean packed = false;

        // Try packing, increase atlas size if it fails
        while(!packed) {
            newAtlas = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = newAtlas.createGraphics();

            int currentX = 0;
            int currentY = 0;
            int rowHeight = 0;
            boolean fit = true;

            for (UniqueTextureArea area : areas) {
                if (currentX + area.width > newWidth) { // Next row
                    currentX = 0;
                    currentY += rowHeight;
                    rowHeight = 0;
                }
                if (currentY + area.height > newHeight) { // Doesn't fit
                    fit = false;
                    break;
                }

                g.drawImage(area.canonicalImage, currentX, currentY, null);
                area.setNewCoordinates(currentX, currentY); // Store new coords

                currentX += area.width;
                rowHeight = Math.max(rowHeight, area.height);
            }
            g.dispose();

            if (fit) {
                packed = true;
            } else {
                // 2x the size
                newWidth *= 2;
                newHeight *= 2;
                System.out.println("  ...Atlas not big enough, retrying with " + newWidth + "x" + newHeight);
            }
        }

        return new PackedTexture(originalKey, newAtlas, areas);
    }


    /**
     * Step 6: Patch up the UV's in the item model.
     */
    private void patchModels(Set<Key> modelKeys, PackedTexture packedResult, Key originalTextureKey) throws IOException {
        Key newTextureKey = packedResult.newTextureKey();

        // Build a map of Face -> New UV info for quick patching
        Map<Model.Element.Face, float[]> facePatchMap = new HashMap<>();

        int atlasW = packedResult.newAtlas().getWidth();
        int atlasH = packedResult.newAtlas().getHeight();

        for (UniqueTextureArea uniqueArea : packedResult.packedAreas()) {
            // 1. Get the new UV for the *canonical* patch
            float u1 = (float) uniqueArea.newX / atlasW * 16.0f;
            float v1 = (float) uniqueArea.newY / atlasH * 16.0f;
            float u2 = (float) (uniqueArea.newX + uniqueArea.width) / atlasW * 16.0f;
            float v2 = (float) (uniqueArea.newY + uniqueArea.height) / atlasH * 16.0f;
            float[] canonicalUV = new float[] { u1, v1, u2, v2 };

            for (UniqueTextureArea.Match match : uniqueArea.matches) {
                // 2. Apply the deduplication transform to the canonical UV
                // This gives us the UV for the "rendered" patch
                float[] newRenderedUV = applyUVTransform(canonicalUV, match.transform());

                // 3. Re-apply the *original* flip from the face's UVs
                // This converts the "rendered" UV back into the correct JSON UV
                float[] finalUV = unapplyOriginalFlip(newRenderedUV,
                        match.area().originalFlipH(),
                        match.area().originalFlipV());

                facePatchMap.put(match.area().face(), finalUV);
            }
        }

        // Now, iterate through the models and apply the patches
        for (Key modelKey : modelKeys) {
            Model model = modelCache.get(modelKey); // Get cached model

            // Find the old texture variable and remove it
            String varName = null;
            for(var entry : model.textures.entrySet()) {
                if (entry.getValue().isKey() && entry.getValue().key().equals(originalTextureKey)) {
                    varName = entry.getKey();
                    break;
                }
            }
            if (varName != null) {
                model.textures.remove(varName);
            }

            // Add the new atlas texture variable
            model.textures.put(varName, new Model.ModelTexture(newTextureKey, null));

            // Patch all faces
            String asRef = "#" + varName;
            for (Model.Element element : model.elements) {
                if (element.faces == null) continue;
                for (Model.Element.Face face : element.faces.values()) {
                    if (face.texture != null && face.texture.equals(asRef)) {
                        float[] newUV = facePatchMap.get(face);
                        if (newUV != null) {
                            face.uv = newUV;
                        }
                    }
                }
            }

            // Save the modified model
            resourcePack.saveModel(modelKey, model, outputPackPath);
        }
    }

    // --- Image Utility Methods ---

    private BufferedImage flip(BufferedImage img, boolean horizontal, boolean vertical) {
        if (!horizontal && !vertical) return img;

        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage flipped = new BufferedImage(w, h, img.getType());
        Graphics2D g = flipped.createGraphics();

        AffineTransform at = new AffineTransform();
        if (horizontal) {
            at.concatenate(AffineTransform.getScaleInstance(-1, 1));
            at.concatenate(AffineTransform.getTranslateInstance(-w, 0));
        }
        if (vertical) {
            at.concatenate(AffineTransform.getScaleInstance(1, -1));
            at.concatenate(AffineTransform.getTranslateInstance(0, -h));
        }

        g.setTransform(at);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return flipped;
    }

    private boolean areImagesEqual(BufferedImage img1, BufferedImage img2) {
        if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) {
            return false;
        }
        for (int x = 0; x < img1.getWidth(); x++) {
            for (int y = 0; y < img1.getHeight(); y++) {
                if (img1.getRGB(x, y) != img2.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Applies a flip transformation to a UV array. */
    private float[] applyUVTransform(float[] uv, Transform transform) {
        float u1 = uv[0], v1 = uv[1], u2 = uv[2], v2 = uv[3];
        return switch (transform) {
            case NONE -> new float[] { u1, v1, u2, v2 };
            case FLIP_H -> new float[] { u2, v1, u1, v2 }; // Swap U
            case FLIP_V -> new float[] { u1, v2, u2, v1 }; // Swap V
            case FLIP_HV -> new float[] { u2, v2, u1, v1 }; // Swap U & V
        };
    }

    /** Re-applies the original UV flip to a new UV array. */
    private float[] unapplyOriginalFlip(float[] uv, boolean originalFlipH, boolean originalFlipV) {
        float u1 = uv[0], v1 = uv[1], u2 = uv[2], v2 = uv[3];
        if (originalFlipH) {
            float tempU = u1;
            u1 = u2;
            u2 = tempU;
        }
        if (originalFlipV) {
            float tempV = v1;
            v1 = v2;
            v2 = tempV;
        }
        return new float[] { u1, v1, u2, v2 };
    }
}