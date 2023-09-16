package com.cobelpvp.bond;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cobelpvp.bond.packet.Packet;
import com.cobelpvp.bond.packet.handler.PacketExceptionHandler;
import com.cobelpvp.bond.packet.listener.PacketListener;
import com.cobelpvp.bond.packet.listener.PacketListenerData;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

public class Bond {

    private static JsonParser PARSER = new JsonParser();

    private final String channel;

    private JedisPool jedisPool;
    private JedisPubSub jedisPubSub;

    private List<PacketListenerData> packetListeners;

    private Map<Integer, Class> idToType;
    private Map<Class, Integer> typeToId;

    public Bond(String channel, String host, int port, String password) {
        this.packetListeners = new ArrayList<>();
        this.idToType = new HashMap<>();
        this.typeToId = new HashMap<>();
        this.channel = channel;
        this.packetListeners = new ArrayList<>();
        this.jedisPool = new JedisPool(host, port);

        if (password != null && !password.equals("")) {
            try (final Jedis jedis = this.jedisPool.getResource()) {
                jedis.auth(password);
            }
        }
        this.setupPubSub();
    }

    public void sendPacket(Packet packet) {
        sendPacket(packet, null);
    }

    public void sendPacket(Packet packet, PacketExceptionHandler exceptionHandler) {
        try {
            JsonObject object = packet.serialize();

            if (object == null) {
                throw new IllegalStateException("Packet cannot generate null serialized data");
            }

            try (Jedis jedis = this.jedisPool.getResource()) {
                jedis.publish(this.channel, packet.id() + ";" + object.toString());
            }
        } catch (Exception e) {
            if (exceptionHandler != null) {
                exceptionHandler.onException(e);
            }
        }
    }

    public Packet buildPacket(int id) {
        if (!this.idToType.containsKey(id)) {
            throw new IllegalStateException("A packet with that ID does not exist");
        }

        try {
            return (Packet) this.idToType.get(id).newInstance();
        }

        catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException("Could not create new instance of packet type");
        }
    }

    public void registerPacket(Class clazz) {
        try {
            int id = (int) clazz.getDeclaredMethod("id").invoke(clazz.newInstance(), null);

            if (idToType.containsKey(id) || typeToId.containsKey(clazz)) {
                throw new IllegalStateException("A packet with that ID has already been registered");
            }

            idToType.put(id, clazz);
            typeToId.put(clazz, id);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void registerListener(PacketListener packetListener) {
        methodLoop:
        for (Method method : packetListener.getClass().getDeclaredMethods()) {
            Class packetClass = null;
            if (method.getParameters().length > 0) {
                if (Packet.class.isAssignableFrom(method.getParameters()[0].getType())) {
                    packetClass = method.getParameters()[0].getType();
                }
            }

            if (packetClass != null) {
                this.packetListeners.add(new PacketListenerData(packetListener, method, packetClass));
            }
        }
    }

    private void setupPubSub() {
        this.jedisPubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (channel.equalsIgnoreCase(Bond.this.channel)) {
                    try {
                        String[] args = message.split(";");
                        int id = Integer.parseInt(args[0]);
                        Packet packet = buildPacket(id);
                        if (packet != null) {
                            packet.deserialize(PARSER.parse(args[1]).getAsJsonObject());

                            for (PacketListenerData data : packetListeners) {
                                if (data.matches(packet)) {
                                    data.getMethod().invoke(data.getInstance(), packet);
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        };

        ForkJoinPool.commonPool().execute(() -> {
            try (Jedis jedis = this.jedisPool.getResource()) {
                jedis.subscribe(this.jedisPubSub, channel);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
