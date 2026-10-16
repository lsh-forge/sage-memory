package com.pa.sagememory.agent.workspace;

/** 图片身份统一绑定用户级原始消息编号；文件名、JSONL 引用与检索 ID 使用同一规则。 */
public final class ImageIdentity {
    private ImageIdentity() {}

    public static String id(String sourceId, int ordinal) {
        if (sourceId == null || !sourceId.matches("[1-9][0-9]*") || Long.parseLong(sourceId) < 1 || ordinal < 1)
            throw new IllegalArgumentException("Image identity requires a positive source ID and image ordinal.");
        return "p" + sourceId + "-" + ordinal;
    }

    public static String relativePath(String imageId, String mediaType) {
        if (imageId == null || !imageId.matches("p[1-9][0-9]*-[1-9][0-9]*"))
            throw new IllegalArgumentException("Invalid image ID.");
        int separator = imageId.indexOf('-');
        id(imageId.substring(1, separator), Integer.parseInt(imageId.substring(separator + 1)));
        String extension = switch (mediaType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("Unsupported image media type.");
        };
        return "assets/images/" + imageId + "." + extension;
    }
}
