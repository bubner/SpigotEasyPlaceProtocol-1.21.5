package dev.tokasen.easyplaceprotocol;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.StreamSerializer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.MinecraftKey;
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import org.bukkit.Axis;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.craftbukkit.v1_21_R4.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EasyPlaceProtocol extends JavaPlugin implements Listener {

    private final Map<Player, PacketData> playerPacketDataHashMap = new HashMap<>();

    // 預先準備 DiscardedPayload 類別、建構子與 data() 方法
    private final Class<?> dpClass;
    private final Constructor<?> ctorBuf, ctorBytes;
    private final Method dataMethod;

    public EasyPlaceProtocol() throws Exception {
        dpClass = Class.forName("net.minecraft.network.protocol.common.custom.DiscardedPayload");
        Constructor<?> cb = null, cbytes = null;
        // 嘗試兩種建構子
        try {
            cb = dpClass.getConstructor(ResourceLocation.class, ByteBuf.class);
        } catch (NoSuchMethodException ignored) {}
        try {
            cbytes = dpClass.getConstructor(ResourceLocation.class, byte[].class);
        } catch (NoSuchMethodException ignored) {}
        // data() 方法也可能回 ByteBuf 或 byte[]
        dataMethod = dpClass.getMethod("data");
        ctorBuf   = cb;
        ctorBytes = cbytes;
    }

    @Override
    public void onEnable() {
        getLogger().info("SpigotEasyPlaceProtocol loaded!");
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ITEM_ON) {
            @Override
            public void onPacketReceiving(final PacketEvent event) {
                onBlockBuildPacket(event);
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.CUSTOM_PAYLOAD) {
            @Override
            public void onPacketReceiving(final PacketEvent event) {
                onCustomPayload(event);
            }
        });
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        ByteArrayOutputStream rawData = new ByteArrayOutputStream();
        DataOutputStream outputStream = new DataOutputStream(rawData);
        Object key = MinecraftKey.getConverter().getGeneric(new MinecraftKey("carpet", "hello"));

        try {
            StreamSerializer.getDefault().serializeVarInt(outputStream, 69);
            StreamSerializer.getDefault().serializeString(outputStream, "SPIGOT-ABP");
            CustomPacketPayload payload = makePayload(key, rawData.toByteArray());
            sendClientBoundCustomPayloadPacket(event.getPlayer(), payload);
        } catch (IOException ignored) {} catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerPacketDataHashMap.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBuildEvent(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        PacketData packetData = playerPacketDataHashMap.get(player);
        if (packetData == null) {
            return;
        }

        BlockPosition packetBlock = packetData.block();
        Block block = event.getBlock();
        if (packetBlock.getX() != block.getX() || packetBlock.getY() != block.getY() || packetBlock.getZ() != block.getZ()) {
            playerPacketDataHashMap.remove(player);
            return;
        }

        accurateBlockProtocol(event, packetData.protocolValue());
        playerPacketDataHashMap.remove(player);
    }

    private void accurateBlockProtocol(BlockPlaceEvent event, int protocolValue) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Block clickedBlock = event.getBlockAgainst();
        BlockData blockData = block.getBlockData();
        BlockData clickBlockData = clickedBlock.getBlockData();

        if (blockData instanceof Bed) {
            return;
        }

        if (blockData instanceof Directional directional) {
            int facingIndex = protocolValue & 0xF;
            if (facingIndex == 6) {
                directional.setFacing(directional.getFacing().getOppositeFace());
            } else if (facingIndex <= 5) {
                Set<BlockFace> validFaces = directional.getFaces();
                BlockFace face = switch (facingIndex) {
                    case 0 -> BlockFace.DOWN;
                    case 1 -> BlockFace.UP;
                    case 2 -> BlockFace.NORTH;
                    case 3 -> BlockFace.SOUTH;
                    case 4 -> BlockFace.WEST;
                    case 5 -> BlockFace.EAST;
                    default -> null;
                };
                if (validFaces.contains(face)) {
                    directional.setFacing(face);
                }
            }

            //Merge chests if needed.
            if (blockData instanceof Chest chest) {
                //Make sure we don't rotate a "half-double" chest!
                chest.setType(Chest.Type.SINGLE);
                BlockFace left = rotateCW(chest.getFacing());
                // Handle clicking on a chest in the world.
                if (!clickedBlock.equals(block) && clickBlockData.getMaterial() == chest.getMaterial()) {
                    Chest clickChest = (Chest) clickBlockData;
                    if (clickChest.getType() == Chest.Type.SINGLE && chest.getFacing() == clickChest.getFacing()) {
                        BlockFace relation = block.getFace(clickedBlock);
                        if (left == relation) {
                            chest.setType(Chest.Type.LEFT);
                        } else if (left.getOppositeFace() == relation) {
                            chest.setType(Chest.Type.RIGHT);
                        }
                    }
                    // Handle placing a chest normally.
                } else if (!player.isSneaking()) {
                    BlockData leftBlock = block.getRelative(left).getBlockData();
                    BlockData rightBlock = block.getRelative(left.getOppositeFace()).getBlockData();
                    if (leftBlock.getMaterial() == chest.getMaterial() &&
                            ((Chest) leftBlock).getType() == Chest.Type.SINGLE &&
                            ((Chest) leftBlock).getFacing() == chest.getFacing()) {
                        chest.setType(Chest.Type.LEFT);
                    } else if (rightBlock.getMaterial() == chest.getMaterial() &&
                            ((Chest) rightBlock).getType() == Chest.Type.SINGLE &&
                            ((Chest) rightBlock).getFacing() == chest.getFacing()) {
                        chest.setType(Chest.Type.RIGHT);
                    }
                }
            } else if (blockData instanceof Stairs) {
                ((Stairs) blockData).setShape(handleStairs(block, (Stairs) blockData));
            }
        } else if (blockData instanceof Orientable orientable) {
            Set<Axis> validAxes = orientable.getAxes();
            Axis axis = switch (protocolValue % 3) {
                case 0 -> Axis.X;
                case 1 -> Axis.Y;
                case 2 -> Axis.Z;
                default -> null;
            };
            if (validAxes.contains(axis)) {
                assert axis != null;
                orientable.setAxis(axis);
            }
        }

        protocolValue &= 0xFFFFFFF0;
        if (protocolValue >= 16) {
            if (blockData instanceof Repeater repeater) {
                int delay = protocolValue / 16;
                if (delay >= repeater.getMinimumDelay() && delay <= repeater.getMaximumDelay()) {
                    repeater.setDelay(delay);
                }
            } else if (protocolValue == 16) {
                if (blockData instanceof Comparator comparator) {
                    comparator.setMode(Comparator.Mode.SUBTRACT);
                } else if (blockData instanceof Bisected bisected) {
                    bisected.setHalf(Bisected.Half.TOP);
                }
            }
        }

        if (block.canPlace(blockData)) {
            block.setBlockData(blockData);
        } else {
            event.setCancelled(true);
        }
    }

    private BlockFace rotateCW(BlockFace in) {
        BlockFace out;
        switch (in) {
            case NORTH -> out = BlockFace.EAST;
            case WEST -> out = BlockFace.NORTH;
            case SOUTH -> out = BlockFace.WEST;
            case EAST -> out = BlockFace.SOUTH;
            default -> out = in;
        }
        return out;
    }

    private Stairs.Shape handleStairs(Block block, Stairs stairs) {
        Bisected.Half half = stairs.getHalf();
        BlockFace backFace = stairs.getFacing();
        BlockFace frontFace = backFace.getOppositeFace();
        BlockFace rightFace = rotateCW(backFace);
        BlockFace leftFace = rightFace.getOppositeFace();
        Stairs backStairs = block.getRelative(backFace).getBlockData() instanceof Stairs ? (Stairs) block.getRelative(backFace).getBlockData() : null;
        Stairs frontStairs = block.getRelative(frontFace).getBlockData() instanceof Stairs ? (Stairs) block.getRelative(frontFace).getBlockData() : null;
        Stairs leftStairs = block.getRelative(leftFace).getBlockData() instanceof Stairs ? (Stairs) block.getRelative(leftFace).getBlockData() : null;
        Stairs rightStairs = block.getRelative(rightFace).getBlockData() instanceof Stairs ? (Stairs) block.getRelative(rightFace).getBlockData() : null;

        if ((backStairs != null && backStairs.getHalf() == half && backStairs.getFacing() == leftFace) &&
                !(rightStairs != null && rightStairs.getHalf() == half && rightStairs.getFacing() == backFace)) {
            return Stairs.Shape.OUTER_LEFT;
        } else if ((backStairs != null && backStairs.getHalf() == half && backStairs.getFacing() == rightFace) &&
                !(leftStairs != null && leftStairs.getHalf() == half && leftStairs.getFacing() == backFace)) {
            return Stairs.Shape.OUTER_RIGHT;
        } else if ((frontStairs != null && frontStairs.getHalf() == half && frontStairs.getFacing() == leftFace) &&
                !(leftStairs != null && leftStairs.getHalf() == half && leftStairs.getFacing() == backFace)) {
            return Stairs.Shape.INNER_LEFT;
        } else if ((frontStairs != null && frontStairs.getHalf() == half && frontStairs.getFacing() == rightFace) &&
                !(rightStairs != null && rightStairs.getHalf() == half && rightStairs.getFacing() == backFace)) {
            return Stairs.Shape.INNER_RIGHT;
        } else {
            return Stairs.Shape.STRAIGHT;
        }

    }

    private void onBlockBuildPacket(final PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();
        MovingObjectPositionBlock clickInformation = packet.getMovingBlockPositions().read(0);
        BlockPosition blockPosition = clickInformation.getBlockPosition();
        Vector posVector = clickInformation.getPosVector();

        double relativeX = posVector.getX() - blockPosition.getX();

        if (relativeX < 2) {
            playerPacketDataHashMap.remove(player);
            return;
        }
        int protocolValue = ((int) relativeX - 2) / 2;
        playerPacketDataHashMap.put(player, new PacketData(clickInformation.getBlockPosition(), protocolValue));
        int relativeInt = (int) relativeX;
        relativeX -= (relativeInt / 2) * 2; //Remove the largest multiple of 2.
        posVector.setX(relativeX + blockPosition.getX());
        clickInformation.setPosVector(posVector);
        packet.getMovingBlockPositions().write(0, clickInformation);
    }

    private void onCustomPayload(final PacketEvent event) {
        try {
            PacketContainer packet = event.getPacket();
            Object payload = packet.getModifier().read(0);  // 這裡拿到的就是 DiscardedPayload
            // 先用反射讀 id()
            Method idMethod = dpClass.getMethod("id");
            Object resourceLocation = idMethod.invoke(payload);
            Method getNamespace = resourceLocation.getClass().getMethod("getNamespace");
            Method getPath      = resourceLocation.getClass().getMethod("getPath");
            String ns   = (String) getNamespace.invoke(resourceLocation);
            String path = (String) getPath.invoke(resourceLocation);
            if (!("carpet".equals(ns) && "hello".equals(path))) return;

            // 拿到包內資料
            ByteBuf data = extractData(payload);
            DataInputStream in = new DataInputStream(new ByteBufInputStream(data));
            if (StreamSerializer.getDefault().deserializeVarInt(in) != 420) return;

            // 準備要回傳的 NBT
            Object key = MinecraftKey.getConverter().getGeneric(new MinecraftKey("carpet", "hello"));
            NbtCompound abpRule = NbtFactory.ofCompound("Rules", List.of(
                    NbtFactory.of("Value", "true"),
                    NbtFactory.of("Manager", "carpet"),
                    NbtFactory.of("Rule", "accurateBlockPlacement")
            ));
            ByteArrayOutputStream rawData = new ByteArrayOutputStream();
            DataOutputStream outputStream = new DataOutputStream(rawData);
            StreamSerializer.getDefault().serializeVarInt(outputStream, 1);
            StreamSerializer.getDefault().serializeCompound(outputStream, abpRule);

            // 建立並發送回傳包
            CustomPacketPayload rulePayload = makePayload(key, rawData.toByteArray());
            sendClientBoundCustomPayloadPacket(event.getPlayer(), rulePayload);

        } catch (Exception e) {
            // 忽略錯誤或自行處理
        }
    }

    // 用反射建立 DiscardedPayload
    private CustomPacketPayload makePayload(Object key, byte[] raw) throws Exception {
        if (ctorBuf != null) {
            ByteBuf buf = Unpooled.wrappedBuffer(raw);
            return (CustomPacketPayload) ctorBuf.newInstance(key, buf);
        } else {
            return (CustomPacketPayload) ctorBytes.newInstance(key, raw);
        }
    }

    // 用反射讀取 payload 的內部資料，並統一包成 ByteBuf
    private ByteBuf extractData(Object payload) throws Exception {
        Object ret = dataMethod.invoke(payload);
        if (ret instanceof ByteBuf) {
            return (ByteBuf) ret;
        } else if (ret instanceof byte[]) {
            return Unpooled.wrappedBuffer((byte[]) ret);
        } else {
            throw new IllegalStateException("Unknown data() return type: " + ret.getClass());
        }
    }

    private static void sendClientBoundCustomPayloadPacket(Player player, CustomPacketPayload payload) {
        // Convert Bukkit Player to ServerPlayer
        CraftPlayer craftPlayer = (CraftPlayer) player;
        ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(payload);
        // Send packet to player
        ServerCommonPacketListenerImpl connection = craftPlayer.getHandle().connection;
        connection.sendPacket(packet);
    }
}
