package de.tomalbrc.optialg;

import de.tomalbrc.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// we optimize the rp textures in these steps:
// - collect all models without parent (too annoying to handle). we also check if a model is a parent of another model, if the other model has its own elements we skip both. we also exclude animated textures.
// - group models by texture Map<Texture, Set<Model>>
// - get texture patches for each face
// - deduplicate areas
// - re-stitch texture and patch asociated models
// - save new texture and models with updated uv mappings
public class Optimizer {
    // uv-area data for a single face including its texture
    private record TextureArea(
            Key modelKey,
            Model.Element element,
            Model.Element.Face face,
            BufferedImage imagePatch,
            boolean originalFlipH,
            boolean originalFlipV
    ) {}

    // deduplicated texture area and all faces that match it
    private static class UniqueTextureArea {
        final BufferedImage canonicalImage;
        final int width, height;
        final List<Match> matches = new ArrayList<>();
        int newX, newY; // Where it will be placed in the new atlas

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

    private record PackedTexture(
            Key newTextureKey,
            BufferedImage newAtlas,
            List<UniqueTextureArea> packedAreas
    ) {}

    private enum Transform { NONE, FLIP_H, FLIP_V, FLIP_HV }

    private final ResourcePack resourcePack;
    private final Path outputPackPath;

    // we need to keep track of modified models in case their are being modified by multiple textures being re-restitched
    private final Map<Key, Model> modelCache = new ConcurrentHashMap<>();

    public Optimizer(ResourcePack resourcePack, Path outputPackPath) {
        this.resourcePack = resourcePack;
        this.outputPackPath = outputPackPath;
    }

    public void optimize() throws IOException {
        System.out.println("Starting optimization...");

        // find models and group by texture
        Map<Key, Set<Key>> textureToModels = findModelsToOptimize();
        System.out.println("Found " + textureToModels.size() + " texture groups to optimize.");

        AtomicInteger c = new AtomicInteger();

        textureToModels.forEach((originalTextureKey, modelKeys) -> {
            System.out.println("Processing texture: " + originalTextureKey + " (" + modelKeys.size() + " models)");

            try {
                var success = processTextureGroup(originalTextureKey, modelKeys);
                if (success) c.getAndIncrement();
            } catch (Exception e) {
                System.err.println("Failed to process group for texture " + originalTextureKey + ": " + e.getMessage());
                e.printStackTrace();
            }
        });

        System.out.println("Optimized " + c.get() + " textures");
        System.out.println("Optimization complete. Output at: " + outputPackPath);
    }

    private Map<Key, Set<Key>> findModelsToOptimize() throws IOException {
        List<Key> allModels = resourcePack.discoverAllModels();
        Map<Key, Set<Key>> textureToModels = new HashMap<>();

        Map<Key, List<Key>> parentToChildren = new HashMap<>();
        for (Key childKey : allModels) {
            Model childModel = resourcePack.getModel(childKey);
            if (childModel == null) continue;
            if (childModel.parent != null) {
                parentToChildren.computeIfAbsent(childModel.parent, k -> new ArrayList<>()).add(childKey);
            }
        }

        for (Key modelKey : allModels) {
            Model model = resourcePack.getModel(modelKey);

            if (model == null || model.parent != null) continue;
            if (model.elements == null || model.elements.isEmpty()) continue;
            if (model.textures == null || model.textures.isEmpty()) continue;

            // if this model is a parent of other models, exclude it UNLESS all children have no elements
            if (parentToChildren.containsKey(modelKey)) {
                boolean anyChildHasElements = false;
                for (Key childKey : parentToChildren.get(modelKey)) {
                    Model child = resourcePack.getModel(childKey);
                    if (child != null) {
                        for (Key key : child.getNonParticleTextures()) {
                            if (resourcePack.hasTextureMeta(key)) {
                                anyChildHasElements = true;
                                break;
                            }
                        }

                        if (child.elements != null && !child.elements.isEmpty()) {
                            anyChildHasElements = true;
                            break;
                        }
                    }
                }
                if (anyChildHasElements) {
                    // skip parent because at least one child defines its own geometry
                    continue;
                }
                // else: all children have no elements -> safe to consider the parent
            }

            Set<Key> nonParticle = model.getNonParticleTextures();
            if (nonParticle == null || nonParticle.isEmpty()) continue;

            boolean hasMetaTexture = false;
            for (Key key : nonParticle) {
                hasMetaTexture = resourcePack.hasTextureMeta(key);
                if (hasMetaTexture)
                    break;
            }

            if (hasMetaTexture)
                continue;

            modelCache.put(modelKey, model);

            // group models by texture
            for (Key texKey : nonParticle) {
                if (!resourcePack.hasTexture(texKey) || resourcePack.hasTextureMeta(texKey)) continue;
                textureToModels.computeIfAbsent(texKey, k -> new HashSet<>()).add(modelKey);
            }
        }

        return textureToModels;
    }

    private boolean processTextureGroup(Key textureKey, Set<Key> modelKeys) throws IOException {
        Texture originalTexture = resourcePack.getTexture(textureKey);

        List<TextureArea> allAreas = extractAllTextureAreas(modelKeys, originalTexture);
        if (allAreas.isEmpty()) {
            System.out.println("WARNING:  ...No valid texture areas found, skipping.");
            return false;
        }

        // deduplicate texture areas
        List<UniqueTextureArea> uniqueAreas = deduplicateAreas(allAreas);

        // repack into a new texture atlas
        PackedTexture packedResult = packTexture(uniqueAreas, textureKey);

        int origW = originalTexture.image().getWidth();
        int origH = originalTexture.image().getHeight();
        int newW = packedResult.newAtlas().getWidth();
        int newH = packedResult.newAtlas().getHeight();

        // only save models and texture if we made it smaller
        if (newW < origW || newH < origH) {
            resourcePack.saveTexture(textureKey, packedResult.newAtlas(), outputPackPath);
            System.out.println("Saved optimized texture");

            patchModels(modelKeys, packedResult, textureKey);

            return true;
        }

        return false;
    }

    private List<TextureArea> extractAllTextureAreas(Set<Key> modelKeys, Texture originalTexture) {
        List<TextureArea> allAreas = new ArrayList<>();
        BufferedImage originalImage = originalTexture.image();
        int imgW = originalImage.getWidth();
        int imgH = originalImage.getHeight();

        for (Key modelKey : modelKeys) {
            Model model = modelCache.get(modelKey); // Get from cache
            if (model == null || model.elements == null) continue;

            Map<String, Key> faceRefToTextureKey = new HashMap<>();
            if (model.textures != null) {
                for (var entry : model.textures.entrySet()) {
                    String varName = entry.getKey();
                    Model.ModelTexture texVal = entry.getValue();
                    if (varName == null || varName.equals("particle") || texVal == null) continue;

                    // TODO: resolve..!
                    if (texVal.isKey()) {
                        Key resolved = texVal.key();
                        if (resolved != null && resourcePack.hasTexture(resolved)) {
                            faceRefToTextureKey.put("#" + varName, resolved);
                        }
                    }
                }
            }

            for (Model.Element element : model.elements) {
                if (element.faces == null) continue;
                for (Model.Element.Face face : element.faces.values()) {
                    try {
                        if (face.texture == null) continue;

                        Key faceTexKey = null;
                        String faceTex = face.texture;
                        if (faceTex.startsWith("#")) {
                            faceTexKey = faceRefToTextureKey.get(faceTex);
                        } else {
                            // TODO: are direct texture references a thing with mc models?
                            continue;
                        }

                        if (faceTexKey == null) continue;
                        if (!faceTexKey.equals(originalTexture.key())) continue;

                        if (face.uv == null) continue;
                        float[] uv = face.uv;
                        double x1_p = (uv[0] / 16.0) * imgW;
                        double y1_p = (uv[1] / 16.0) * imgH;
                        double x2_p = (uv[2] / 16.0) * imgW;
                        double y2_p = (uv[3] / 16.0) * imgH;

                        // pixel perfect bb
                        int x = (int) Math.round(Math.min(x1_p, x2_p));
                        int y = (int) Math.round(Math.min(y1_p, y2_p));
                        int w = (int) Math.round(Math.abs(x2_p - x1_p));
                        int h = (int) Math.round(Math.abs(y2_p - y1_p));

                        if (w <= 0 || h <= 0) continue; // degenerate face

                        BufferedImage patch;
                        if (x >= 0 && y >= 0 && x + w <= imgW && y + h <= imgH) {
                            patch = originalImage.getSubimage(x, y, w, h);
                        } else {
                            System.out.println("Warning: Wrapping texture values out of bounds. Check your model! " + modelKey.toString());

                            int imgType = originalImage.getType();
                            if (imgType == BufferedImage.TYPE_CUSTOM) {
                                imgType = BufferedImage.TYPE_INT_ARGB;
                            }
                            patch = new BufferedImage(w, h, imgType);

                            for (int yy = 0; yy < h; yy++) {
                                int srcY = y + yy;
                                srcY %= imgH;
                                if (srcY < 0) srcY += imgW;

                                for (int xx = 0; xx < w; xx++) {
                                    int srcX = x + xx;
                                    srcX %= imgW;
                                    if (srcX < 0) srcX += imgW;

                                    int rgb = originalImage.getRGB(srcX, srcY);
                                    patch.setRGB(xx, yy, rgb);
                                }
                            }
                        }

                        boolean originalFlipH = uv[0] > uv[2];
                        boolean originalFlipV = uv[1] > uv[3];
                        allAreas.add(new TextureArea(modelKey, element, face, patch, originalFlipH, originalFlipV));
                    } catch (Exception e) {
                        System.err.println("  ...Skipping face due to error: " + e.getMessage() + " on " + modelKey);
                    }
                }
            }
        }
        return allAreas;
    }

    private List<UniqueTextureArea> deduplicateAreas(List<TextureArea> allAreas) {
        List<UniqueTextureArea> uniqueList = new ArrayList<>();

        for (TextureArea area : allAreas) {
            BufferedImage renderedPatch = ImageUtil.flip(area.imagePatch(), area.originalFlipH(), area.originalFlipV());

            UniqueTextureArea match = null;
            Transform matchTransform = Transform.NONE;

            for (UniqueTextureArea unique : uniqueList) {
                if (ImageUtil.areImagesEqual(renderedPatch, unique.canonicalImage)) {
                    match = unique;
                    break;
                }

                if (ImageUtil.areImagesEqual(renderedPatch, ImageUtil.flip(unique.canonicalImage, true, false))) {
                    match = unique;
                    matchTransform = Transform.FLIP_H;
                    break;
                }
                if (ImageUtil.areImagesEqual(renderedPatch, ImageUtil.flip(unique.canonicalImage, false, true))) {
                    match = unique;
                    matchTransform = Transform.FLIP_V;
                    break;
                }
                if (ImageUtil.areImagesEqual(renderedPatch, ImageUtil.flip(unique.canonicalImage, true, true))) {
                    match = unique;
                    matchTransform = Transform.FLIP_HV;
                    break;
                }
            }

            if (match != null) {
                match.addMatch(area, matchTransform);
            } else {
                uniqueList.add(new UniqueTextureArea(renderedPatch, area, Transform.NONE));
            }
        }
        return uniqueList;
    }

    // maxRects like packing (short-side fit)
    private PackedTexture packTexture(List<UniqueTextureArea> areas, Key originalKey) {
        // sort by max side (descending)
        areas.sort((a, b) -> Integer.compare(Math.max(b.width, b.height), Math.max(a.width, a.height)));

        int maxW = 16, maxH = 16;
        for (UniqueTextureArea a : areas) {
            maxW = Math.max(maxW, a.width);
            maxH = Math.max(maxH, a.height);
        }

        int width = nextPowerOfTwo(maxW);
        int height = nextPowerOfTwo(maxH);

        List<UniqueTextureArea> placed = null;

        final int MAX_SIZE = 16384;

        while (true) {
            if (width > MAX_SIZE || height > MAX_SIZE) {
                throw new RuntimeException("Error while packing texture - could not fit " + originalKey.toString());
            }

            var attempt = tryPackWithMaxRects(width, height, areas);
            if (attempt != null) {
                placed = attempt;
                break;
            }

            // grow the smaller side
            if (width <= height) width *= 2;
            else height *= 2;
        }

        BufferedImage newAtlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = newAtlas.createGraphics();
        for (UniqueTextureArea a : placed) {
            g.drawImage(a.canonicalImage, a.newX, a.newY, null);
        }
        g.dispose();

        return new PackedTexture(originalKey, newAtlas, placed);
    }

    private List<UniqueTextureArea> tryPackWithMaxRects(int atlasW, int atlasH, List<UniqueTextureArea> areas) {
        List<Rect> freeList = new ArrayList<>();
        freeList.add(new Rect(0, 0, atlasW, atlasH));

        List<UniqueTextureArea> placed = new ArrayList<>();

        for (UniqueTextureArea area : areas) {
            Rect bestNode = null;
            int bestShortSideFit = Integer.MAX_VALUE;
            int bestLongSideFit = Integer.MAX_VALUE;

            // find best free rect for this area
            for (Rect free : freeList) {
                if (area.width <= free.w && area.height <= free.h) {
                    int leftoverHoriz = Math.abs(free.w - area.width);
                    int leftoverVert = Math.abs(free.h - area.height);
                    int shortSideFit = Math.min(leftoverHoriz, leftoverVert);
                    int longSideFit = Math.max(leftoverHoriz, leftoverVert);

                    if (shortSideFit < bestShortSideFit ||
                            (shortSideFit == bestShortSideFit && longSideFit < bestLongSideFit)) {
                        bestNode = new Rect(free.x, free.y, area.width, area.height);
                        bestShortSideFit = shortSideFit;
                        bestLongSideFit = longSideFit;
                    }
                }
            }

            if (bestNode == null) {
                // cannot place this rectangle
                return null;
            }

            // place it
            area.setNewCoordinates(bestNode.x, bestNode.y);
            placed.add(area);

            // split all free rects that intersect with the placed rect
            List<Rect> toAdd = new ArrayList<>();
            Iterator<Rect> it = freeList.iterator();
            while (it.hasNext()) {
                Rect free = it.next();
                if (!rectsIntersect(free, bestNode)) continue;

                // remove the free rect and produce split rects
                it.remove();

                // split horizontally
                if (bestNode.x > free.x && bestNode.x < free.x + free.w) {
                    toAdd.add(new Rect(free.x, free.y, bestNode.x - free.x, free.h));
                }
                if (bestNode.x + bestNode.w < free.x + free.w) {
                    toAdd.add(new Rect(bestNode.x + bestNode.w, free.y, (free.x + free.w) - (bestNode.x + bestNode.w), free.h));
                }
                // split vertically
                if (bestNode.y > free.y && bestNode.y < free.y + free.h) {
                    toAdd.add(new Rect(free.x, free.y, free.w, bestNode.y - free.y));
                }
                if (bestNode.y + bestNode.h < free.y + free.h) {
                    toAdd.add(new Rect(free.x, bestNode.y + bestNode.h, free.w, (free.y + free.h) - (bestNode.y + bestNode.h)));
                }
            }

            // add new free rects
            for (Rect r : toAdd) {
                if (r.w > 0 && r.h > 0) freeList.add(r);
            }

            // prune free list: remove any rect that is contained in another
            pruneFreeList(freeList);
        }

        return placed;
    }

    private static boolean rectsIntersect(Rect a, Rect b) {
        return !(b.x >= a.x + a.w || b.x + b.w <= a.x || b.y >= a.y + a.h || b.y + b.h <= a.y);
    }

    private static void pruneFreeList(List<Rect> freeList) {
        for (int i = 0; i < freeList.size(); i++) {
            Rect a = freeList.get(i);
            for (int j = i + 1; j < freeList.size(); j++) {
                Rect b = freeList.get(j);
                if (a.contains(b)) {
                    freeList.remove(i);
                    i--;
                    break;
                }
                if (b.contains(a)) {
                    freeList.remove(j);
                    j--;
                }
            }
        }
    }

    private static int nextPowerOfTwo(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    // patch up the uv's in the item models
    private void patchModels(Set<Key> modelKeys, PackedTexture packedResult, Key originalTextureKey) throws IOException {
        Key newTextureKey = packedResult.newTextureKey();

        Map<Model.Element.Face, float[]> facePatchMap = new HashMap<>();

        int atlasW = packedResult.newAtlas().getWidth();
        int atlasH = packedResult.newAtlas().getHeight();

        for (UniqueTextureArea uniqueArea : packedResult.packedAreas()) {
            float u1 = (float) uniqueArea.newX / atlasW * 16.0f;
            float v1 = (float) uniqueArea.newY / atlasH * 16.0f;
            float u2 = (float) (uniqueArea.newX + uniqueArea.width) / atlasW * 16.0f;
            float v2 = (float) (uniqueArea.newY + uniqueArea.height) / atlasH * 16.0f;
            float[] canonicalUV = new float[] { u1, v1, u2, v2 };

            for (UniqueTextureArea.Match match : uniqueArea.matches) {
                // apply deduplication transform
                float[] newRenderedUV = applyUVTransform(canonicalUV, match.transform());

                // re-apply original flip
                // TODO: not sure if actually needed or good idea
                float[] finalUV = unapplyOriginalFlip(newRenderedUV,
                        match.area().originalFlipH(),
                        match.area().originalFlipV());

                facePatchMap.put(match.area().face(), newRenderedUV);
            }
        }

        // apply patches
        for (Key modelKey : modelKeys) {
            Model model = modelCache.get(modelKey); // ALWAYS USE CACHED MODEL
            if (model == null) continue;

            // TODO: not sure if needed if we dont "combine" textures
            // updated references
            if (model.textures != null) {
                for (var entry : new ArrayList<>(model.textures.entrySet())) {
                    String varName = entry.getKey();
                    Model.ModelTexture texVal = entry.getValue();
                    if (texVal == null) continue;
                    if (texVal.isKey() && originalTextureKey.equals(texVal.key())) {
                        model.textures.put(varName, new Model.ModelTexture(newTextureKey, null));
                    }
                }
            }

            // apply replacement if it exists for face
            for (Model.Element element : model.elements) {
                if (element.faces == null) continue;
                for (Model.Element.Face face : element.faces.values()) {
                    float[] newUV = facePatchMap.get(face);
                    if (newUV != null) {
                        face.uv = newUV;
                    }
                }
            }

            resourcePack.saveModel(modelKey, model, outputPackPath);
        }
    }


    private float[] applyUVTransform(float[] uv, Transform transform) {
        float u1 = uv[0], v1 = uv[1], u2 = uv[2], v2 = uv[3];
        return switch (transform) {
            case NONE -> new float[] { u1, v1, u2, v2 };
            case FLIP_H -> new float[] { u2, v1, u1, v2 }; // Swap U
            case FLIP_V -> new float[] { u1, v2, u2, v1 }; // Swap V
            case FLIP_HV -> new float[] { u2, v2, u1, v1 }; // Swap U & V
        };
    }

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
