package com.junesoon.mim.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * @description Jackson工具类
 * @auther didi
 * @date 2022/8/5 18:55
 */
@Slf4j
public class JacksonUtil {

    /**
     * 序列化级别，默认只序列化属性值发生过改变的字段
     */
    private static JsonInclude.Include DEFAULT_PROPERTY_INCLUSION = JsonInclude.Include.NON_NULL;

    private static ObjectMapper mapper = new ObjectMapper();

    /**
     * JSON反序列化
     */
    public static <V> V from(URL url, Class<V> c) {
        try {
            return mapper.readValue(url, c);
        } catch (IOException e) {
            log.error("jackson from error, url: {}, type: {}", url.getPath(), c, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(InputStream inputStream, Class<V> c) {
        try {
            return mapper.readValue(inputStream, c);
        } catch (IOException e) {
            log.error("jackson from error, type: {}", c, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(File file, Class<V> c) {
        try {
            return mapper.readValue(file, c);
        } catch (IOException e) {
            log.error("jackson from error, file path: {}, type: {}", file.getPath(), c, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(Object jsonObj, Class<V> c) {
        try {
            return mapper.readValue(jsonObj.toString(), c);
        } catch (IOException e) {
            log.error("jackson from error, json: {}, type: {}", jsonObj.toString(), c, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(String json, Class<V> c) {
        try {
            return mapper.readValue(json, c);
        } catch (IOException e) {
            log.error("jackson from error, json: {}, type: {}", json, c, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(URL url, TypeReference<V> type) {
        try {
            return mapper.readValue(url, type);
        } catch (IOException e) {
            log.error("jackson from error, url: {}, type: {}", url.getPath(), type, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(InputStream inputStream, TypeReference<V> type) {
        try {
            return mapper.readValue(inputStream, type);
        } catch (IOException e) {
            log.error("jackson from error, type: {}", type, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(File file, TypeReference<V> type) {
        try {
            return mapper.readValue(file, type);
        } catch (IOException e) {
            log.error("jackson from error, file path: {}, type: {}", file.getPath(), type, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(Object jsonObj, TypeReference<V> type) {
        try {
            return mapper.readValue(jsonObj.toString(), type);
        } catch (IOException e) {
            log.error("jackson from error, json: {}, type: {}", jsonObj.toString(), type, e);
            return null;
        }
    }

    /**
     * JSON反序列化
     */
    public static <V> V from(String json, TypeReference<V> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            log.error("jackson from error, json: {}, type: {}", json, type, e);
            return null;
        }
    }

    /**
     * 序列化为JSON
     */
    public static <V> String to(List<V> list) {
        try {
            return mapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.error("jackson to error, obj: {}", list, e);
            return null;
        }
    }

    /**
     * 序列化为JSON
     */
    public static <V> String to(V v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            log.error("jackson to error, obj: {}", v, e);
            return null;
        }
    }

    /**
     * 字符串转JsonNode
     *
     * @return JsonNode
     */
    public static JsonNode strToJsonNode(String json) {

        try {
            JsonNode node = mapper.readTree(json);
            return node;
        } catch (Exception e) {
            log.error("str to jsonNode error, json: {}, e: {}", json, e);
            return null;
        }
    }

    /**
     * 字符串转ArrayNode
     *
     * @return ArrayNode
     */
    public static ArrayNode strToArrayNode(String jsonStr, String key) {

        try {
            ArrayNode arrayNode = (ArrayNode) mapper.readTree(jsonStr).get(key);
            return arrayNode;
        } catch (Exception e) {
            log.error("str to ArrayNode error, json: {}, e: {}", jsonStr, e);
            return null;
        }
    }

    /**
     * 从json串中获取某个字段
     *
     * @return String
     */
    public static String getString(String json, String key) {
        if (json == null || "".equals(json)) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (null != node) {
                return node.get(key).toString();
            } else {
                return null;
            }
        } catch (IOException e) {
            log.error("jackson get string error, json: {}, pullWaybillKey: {}", json, key, e);
            return null;
        }
    }

    public static <T> T json2GenericObject(String jsonString, TypeReference<T> tr) {
        if (jsonString != null && !("".equals(jsonString))) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                return (T) (tr.getType().equals(String.class) ?
                    jsonString :
                    objectMapper.readValue(jsonString, tr));
            } catch (Exception e) {
                log.warn("json error:" + e.getMessage());
            }
        }
        return null;
    }

    /**
     * json字符串转list
     *
     * @return list
     */
    public static <T> List<T> strTransferList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<T>>() {
            });
        } catch (Exception e) {
            log.error("str to ArrayNode error, json: {}, e: {}", json, e);
            return new ArrayList<>(1);
        }
    }

    /**
     * 从json串中获取某个字段
     *
     * @return boolean, 默认为false
     */
    public static <T> ArrayList<T> getList(String json, String key) {
        if (json == null || "".equals(json)) {
            return null;
        }
        String string = getString(json, key);
        return from(string, new TypeReference<ArrayList<T>>() {
        });
    }

    /**
     * 向json中添加属性
     *
     * @return json
     */
    public static <T> String add(String json, String key, T value) {
        try {
            JsonNode node = mapper.readTree(json);
            add(node, key, value);
            return node.toString();
        } catch (IOException e) {
            log.error("jackson add error, json: {}, pullWaybillKey: {}, value: {}", json, key, value, e);
            return json;
        }
    }

    /**
     * 向json中添加属性
     */
    private static <T> void add(JsonNode jsonNode, String key, T value) {
        if (value instanceof String) {
            ((ObjectNode) jsonNode).put(key, (String) value);
        } else if (value instanceof Short) {
            ((ObjectNode) jsonNode).put(key, (Short) value);
        } else if (value instanceof Integer) {
            ((ObjectNode) jsonNode).put(key, (Integer) value);
        } else if (value instanceof Long) {
            ((ObjectNode) jsonNode).put(key, (Long) value);
        } else if (value instanceof Float) {
            ((ObjectNode) jsonNode).put(key, (Float) value);
        } else if (value instanceof Double) {
            ((ObjectNode) jsonNode).put(key, (Double) value);
        } else if (value instanceof BigDecimal) {
            ((ObjectNode) jsonNode).put(key, (BigDecimal) value);
        } else if (value instanceof BigInteger) {
            ((ObjectNode) jsonNode).put(key, (BigInteger) value);
        } else if (value instanceof Boolean) {
            ((ObjectNode) jsonNode).put(key, (Boolean) value);
        } else if (value instanceof byte[]) {
            ((ObjectNode) jsonNode).put(key, (byte[]) value);
        } else {
            ((ObjectNode) jsonNode).put(key, to(value));
        }
    }

    /**
     * 修改json中的属性
     */
    public static <T> String update(String json, String key, T value) {
        try {
            JsonNode node = mapper.readTree(json);
            ((ObjectNode) node).remove(key);
            add(node, key, value);
            return node.toString();
        } catch (IOException e) {
            log.error("jackson update error, json: {}, pullWaybillKey: {}, value: {}", json, key, value, e);
            return json;
        }
    }

}


