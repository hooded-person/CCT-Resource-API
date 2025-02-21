package org.firedragon91245.cctresourceapi.cct;

import dan200.computercraft.api.lua.LuaException;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.firedragon91245.cctresourceapi.CCT_Resource_API;
import org.firedragon91245.cctresourceapi.entity.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

public class ResourceLoading {
    @Nullable
    protected static BlockModelInfo loadBlockModelInfoByBlockId(@Nonnull ResourceLocation location) {
        BlockModelInfo modelInfo = new BlockModelInfo(location);
        if (location.getNamespace().equals("minecraft")) {
            String blockId = location.getPath();
            Optional<String> stateModelJson = loadBundledFileText("bundled_resources/minecraft/blockstates/" + blockId + ".json");
            if (stateModelJson.isPresent()) {
                return loadStatefulBlockModelInfo(modelInfo, stateModelJson.get());
            } else {
                Optional<String> modelJson = loadBundledFileText("bundled_resources/minecraft/models/block/" + blockId + ".json");
                if (modelJson.isPresent()) {
                    BlockModel model = CCT_Resource_API.GSON.fromJson(modelJson.get(), BlockModel.class);
                    modelInfo.statefullModel = false;
                    modelInfo.rootModel = model;
                    modelInfo.models.put("minecraft:block/" + blockId, model);

                    modelInfo.models.putAll(getParentModelsRecursive(modelInfo));
                    loadModelTextures(modelInfo);
                    return modelInfo;
                }
                else {
                    return null;
                }
            }

        } else {
            File f = getModJarFromModId(location.getNamespace()).orElse(null);
            if (f == null)
                return null;

            URL jarUrl = null;
            try {
                jarUrl = new URL("jar:file:" + f.getAbsolutePath() + "!/");
            } catch (MalformedURLException ignored) {
            }
            if(jarUrl == null)
                return null;

            try(URLClassLoader loader = new URLClassLoader(new URL[]{ jarUrl }))
            {
                Optional<String> stateModelJson = loadFileText(loader, "assets/" + location.getNamespace() + "/blockstates/" + location.getPath() + ".json");
                if (stateModelJson.isPresent()) {
                    return loadStatefulBlockModelInfo(modelInfo, stateModelJson.get());
                } else {
                    Optional<String> modelJson = loadFileText(loader, "assets/" + location.getNamespace() + "/models/block/" + location.getPath() + ".json");
                    if (modelJson.isPresent()) {
                        BlockModel model = CCT_Resource_API.GSON.fromJson(modelJson.get(), BlockModel.class);
                        modelInfo.statefullModel = false;
                        modelInfo.rootModel = model;
                        modelInfo.models.put(location.getNamespace() + ":block/" + location.getPath(), model);

                        modelInfo.models.putAll(getParentModelsRecursive(modelInfo));
                        loadModelTextures(modelInfo);
                        return modelInfo;
                    }
                }
            } catch (IOException e) {
                CCT_Resource_API.LOGGER.error("Failed to load mod jar", e);
            }

        }
        return null;
    }

    protected static HashMap<String, BlockModel> getParentModelsRecursive(BlockModelInfo modelInfo) {
        HashMap<String, BlockModel> newModelsCollector = new HashMap<>();
        HashMap<String, BlockModel> newModels = new HashMap<>();
        do {
            newModels.clear();
            modelInfo.models.forEach((key, value) -> {
                if (value != null && value.parent != null) {
                    if (modelInfo.models.containsKey(value.parent) || newModels.containsKey(value.parent) || newModelsCollector.containsKey(value.parent))
                        return;
                    BlockModel parentModel = loadBlockModelByLocation(value.parent);
                    newModels.put(value.parent, parentModel);
                }
            });
            newModelsCollector.putAll(newModels);
        } while (!newModels.isEmpty());

        return newModelsCollector;
    }

    protected static BlockModelInfo loadStatefulBlockModelInfo(BlockModelInfo modelInfo, String stateModelJson) {
        BlockStateModel stateModel = CCT_Resource_API.GSON.fromJson(stateModelJson, BlockStateModel.class);
        modelInfo.statefullModel = true;
        modelInfo.modelState = stateModel;
        modelInfo.modelState.variants.forEach((key, value) -> {
            if(value == null)
                return;
            value.ifOneOrElse(
                    one ->
                    {
                        if(modelInfo.models.containsKey(one.model))
                            return;
                        BlockModel model = loadBlockModelByLocation(one.model);
                        modelInfo.models.put(one.model, model);
                    },
                    more -> {
                        for (BlockStateModelVariant variant : more) {
                            if(modelInfo.models.containsKey(variant.model))
                                continue;
                            BlockModel model = loadBlockModelByLocation(variant.model);
                            modelInfo.models.put(variant.model, model);
                        }
                    });
        });

        modelInfo.models.putAll(getParentModelsRecursive(modelInfo));
        loadModelTextures(modelInfo);
        return modelInfo;
    }

    protected static Optional<ModelTexture> loadFileImage(ClassLoader loader, String location) {
        try (InputStream modelStream = loader.getResourceAsStream(location)) {
            return readInStreamImage(modelStream);
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    protected static Optional<ModelTexture> loadFileBundledImage(String location) {
        try (InputStream modelStream = CCT_Resource_API.class.getClassLoader().getResourceAsStream(location)) {
            return readInStreamImage(modelStream);
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    protected static Optional<ModelTexture> readInStreamImage(InputStream modelStream) {
        if (modelStream == null) {
            return Optional.empty();
        }

        try (ImageInputStream imageStream = ImageIO.createImageInputStream(modelStream)) {
            Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(imageStream);
            if (!imageReaders.hasNext()) {
                return Optional.empty();
            }

            ImageReader reader = imageReaders.next();
            reader.setInput(imageStream);
            String formatName = reader.getFormatName();

            BufferedImage image = reader.read(0, reader.getDefaultReadParam());

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ImageIO.write(image, formatName, byteArrayOutputStream);
            byte[] imageBytes = byteArrayOutputStream.toByteArray();

            ModelTexture modelTexture = new ModelTexture(formatName, image, imageBytes);
            return Optional.of(modelTexture);
        } catch (IOException e) {
            CCT_Resource_API.LOGGER.error("Failed to read image", e);
            return Optional.empty();
        }
    }

    @Nonnull
    protected static Optional<String> readInStreamAll(InputStream modelStream) throws IOException {
        if (modelStream != null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(modelStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return Optional.of(sb.toString());
        }
        return Optional.empty();
    }

    private static Optional<ModelTexture> getModelTexture(String texture) {
        if(!texture.contains(":"))
        {
            texture = "minecraft:" + texture;
        }
        if (texture.startsWith("minecraft:")) {
            String textureId = texture.substring(10);
            return loadFileBundledImage("bundled_resources/minecraft/textures/" + textureId + ".png");
        } else {
            File f = getModJarFromModId(texture.split(":")[0]).orElse(null);
            if (f == null)
                return Optional.empty();

            URL jarUrl = null;
            try {
                jarUrl = new URL("jar:file:" + f.getAbsolutePath() + "!/");
            } catch (MalformedURLException ignored) {
            }

            if(jarUrl == null)
                return Optional.empty();

            try(URLClassLoader loader = new URLClassLoader(new URL[]{ jarUrl }))
            {
                return loadFileImage(loader, "assets/" + texture.split(":")[0] + "/textures/" + texture.split(":")[1] + ".png");
            } catch (IOException e) {
                CCT_Resource_API.LOGGER.error("Failed to load mod jar", e);
            }
            return Optional.empty();
        }
    }

    protected static BlockModel loadBlockModelByLocation(String model) {
        if(!model.contains(":"))
        {
            model = "minecraft:" + model;
        }
        if (model.startsWith("minecraft:")) {
            String modelid = model.substring(10);
            Optional<String> modelJson = loadBundledFileText("bundled_resources/minecraft/models/" + modelid + ".json");
            return modelJson.map(s -> CCT_Resource_API.GSON.fromJson(s, BlockModel.class)).orElse(null);
        } else {
            File f = getModJarFromModId(model.split(":")[0]).orElse(null);
            if (f == null)
                return null;

            URL jarUrl = null;
            try {
                jarUrl = new URL("jar:file:" + f.getAbsolutePath() + "!/");
            } catch (MalformedURLException ignored) {
            }

            if(jarUrl == null)
                return null;

            try(URLClassLoader loader = new URLClassLoader(new URL[]{ jarUrl }))
            {
                Optional<String> modelJson = loadFileText(loader, "assets/" + model.split(":")[0] + "/models/" + model.split(":")[1] + ".json");
                return modelJson.map(s -> CCT_Resource_API.GSON.fromJson(s, BlockModel.class)).orElse(null);
            } catch (IOException e) {
                CCT_Resource_API.LOGGER.error("Failed to load mod jar", e);
            }
            return null;
        }
    }

    protected static Optional<File> getModJarFromModId(String modid) {
        Optional<ModFile> file = ModList.get().getMods().stream()
                .filter(modContainer -> modContainer.getModId().equals(modid))
                .map(modContainer -> {
                    ModFileInfo modFileInfo = modContainer.getOwningFile();
                    if (modFileInfo != null) {
                        return modFileInfo.getFile();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst();

        return file.map(ModFile::getFilePath).map(Path::toFile);
    }

    protected static Optional<String> loadBundledFileText(String location) {
        try (InputStream modelStream = CCT_Resource_API.class.getClassLoader().getResourceAsStream(location)) {
            return readInStreamAll(modelStream);
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    protected static void loadBlockModelInfo(Block b, HashMap<String, Object> blockInfo) {
        ResourceLocation blockId = b.getRegistryName();
        if (blockId == null)
            return;

        BlockModelInfo model = loadBlockModelInfoByBlockId(blockId);
        if (model == null)
            return;

        blockInfo.put("model", model.asHashMap());
    }

    private static Optional<String> loadFileText(ClassLoader loader, String location) {
        try (InputStream modelStream = loader.getResourceAsStream(location)) {
            return readInStreamAll(modelStream);
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    static <TReturn> TReturn loadBufferedImageFromTextureObject(Object image, Map<Object, Color> colorMap, BiFunction<BufferedImage, Map<Object, Color>, TReturn> consumer) throws LuaException {
        if (image instanceof Map) {
            Map<String, Object> imageMap = (Map<String, Object>) image;
            if (!imageMap.containsKey("imageBytes") || !imageMap.containsKey("formatName"))
                return null;

            Object imageBytesObj = imageMap.get("imageBytes");
            Object formatObj = imageMap.get("formatName");
            if (imageBytesObj instanceof Map && formatObj instanceof String) {
                Map<Integer, Double> imageBytes = (Map<Integer, Double>) imageBytesObj;
                Byte[] bytes = imageBytes.entrySet().stream()
                        .sorted(Comparator.comparingInt(Map.Entry::getKey))
                        .map(entry -> entry.getValue().byteValue())
                        .toArray(Byte[]::new);
                String format = (String) formatObj;

                byte[] byteArray = new byte[bytes.length];
                for (int i = 0; i < bytes.length; i++) {
                    byteArray[i] = bytes[i];
                }

                try {
                    BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(byteArray));
                    return consumer.apply(bufferedImage, colorMap);
                } catch (IOException e) {
                    throw new LuaException("Failed to read image bytes");
                }
            }
        }
        return null;
    }

    public static void loadItemModelInfo(Item item, HashMap<String, Object> itemInfo) {
        ResourceLocation itemId = item.getRegistryName();
        if (itemId == null)
            return;

        ItemModelInfo model = loadItemModelInfoByItemId(itemId);
        if (model == null)
            return;

        itemInfo.put("model", model.asHashMap());
    }

    private static URL getModURLFromModId(String modid)
    {
        File f = getModJarFromModId(modid).orElse(null);
        if (f == null)
            return null;

        URL jarUrl = null;
        try {
            jarUrl = new URL("jar:file:" + f.getAbsolutePath() + "!/");
        } catch (MalformedURLException ignored) {
        }
        return jarUrl;
    }

    private static ItemModelInfo loadItemModelInfoByItemId(ResourceLocation itemId) {
        ItemModelInfo modelInfo = new ItemModelInfo(itemId.toString());
        if (itemId.getNamespace().equals("minecraft")) {
            String itemIdStr = itemId.getPath();
            Optional<String> modelJson = loadBundledFileText("bundled_resources/minecraft/models/item/" + itemIdStr + ".json");
            if (modelJson.isPresent()) {
                ItemModel model = CCT_Resource_API.GSON.fromJson(modelJson.get(), ItemModel.class);
                modelInfo.rootModel = model;
                modelInfo.models.put("minecraft:item/" + itemIdStr, model);

                modelInfo.models.putAll(getParentModelsRecursiveItem(modelInfo));
                loadModelTextures(modelInfo);
                return modelInfo;
            }
            else {
                return null;
            }
        } else {
            URL jarUrl = getModURLFromModId(itemId.getNamespace());
            if(jarUrl == null)
                return null;

            try(URLClassLoader loader = new URLClassLoader(new URL[]{ jarUrl }))
            {
                Optional<String> modelJson = loadFileText(loader, "assets/" + itemId.getNamespace() + "/models/item/" + itemId.getPath() + ".json");
                if (modelJson.isPresent()) {
                    ItemModel model = CCT_Resource_API.GSON.fromJson(modelJson.get(), ItemModel.class);
                    modelInfo.rootModel = model;
                    modelInfo.models.put(itemId.getNamespace() + ":item/" + itemId.getPath(), model);

                    modelInfo.models.putAll(getParentModelsRecursiveItem(modelInfo));
                    loadModelTextures(modelInfo);
                    return modelInfo;
                }
            } catch (IOException e) {
                CCT_Resource_API.LOGGER.error("Failed to load mod jar", e);
            }

        }
        return null;
    }

    private static void loadModelTextures(IModelInfo modelInfo)
    {
        modelInfo.getModels().forEach((key, value) -> {
            if (value != null) {
                if (value.getTextures() != null) {
                    value.getTextures().forEach((key1, value1) -> {
                        if (value1 != null && !value1.startsWith("#")) {
                            if(modelInfo.getTextures().containsKey(value1))
                                return;
                            Optional<ModelTexture> texture = getModelTexture(value1);
                            texture.ifPresent(modelTexture -> modelInfo.putTexture(value1, modelTexture));
                        }
                    });
                }
            }
        });
    }

    private static Map<String, ItemModel> getParentModelsRecursiveItem(ItemModelInfo modelInfo) {
        HashMap<String, ItemModel> newModelsCollector = new HashMap<>();
        HashMap<String, ItemModel> newModels = new HashMap<>();
        do {
            newModels.clear();
            modelInfo.models.forEach((key, value) -> {
                if (value != null && value.parent != null) {
                    if (modelInfo.models.containsKey(value.parent) || newModels.containsKey(value.parent) || newModelsCollector.containsKey(value.parent))
                        return;
                    ItemModel parentModel = loadItemModelByLocation(value.parent);
                    newModels.put(value.parent, parentModel);
                }
            });
            newModelsCollector.putAll(newModels);
        } while (!newModels.isEmpty());

        return newModelsCollector;
    }

    private static ItemModel loadItemModelByLocation(String parent) {
        if(!parent.contains(":"))
        {
            parent = "minecraft:" + parent;
        }
        if (parent.startsWith("minecraft:")) {
            String modelid = parent.substring(10);
            Optional<String> modelJson = loadBundledFileText("bundled_resources/minecraft/models/" + modelid + ".json");
            return modelJson.map(s -> CCT_Resource_API.GSON.fromJson(s, ItemModel.class)).orElse(null);
        } else {
            URL jarUrl = getModURLFromModId(parent.split(":")[0]);
            if(jarUrl == null)
                return null;

            try(URLClassLoader loader = new URLClassLoader(new URL[]{ jarUrl }))
            {
                Optional<String> modelJson = loadFileText(loader, "assets/" + parent.split(":")[0] + "/models/" + parent.split(":")[1] + ".json");
                return modelJson.map(s -> CCT_Resource_API.GSON.fromJson(s, ItemModel.class)).orElse(null);
            } catch (IOException e) {
                CCT_Resource_API.LOGGER.error("Failed to load mod jar", e);
            }
            return null;
        }
    }
}
