package dev.tokasen.easyplaceprotocol;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
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
import org.bukkit.craftbukkit.v1_21_R1.entity.CraftPlayer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EasyPlaceProtocol extends JavaPlugin implements Listener {

    private final Map<Player, PacketData> playerPacketDataHashMap = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("EasyPlaceProtocol loaded!");
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
            StreamSerializer.getDefault().serializeString(outputStream, "SPIGOT-ABP");//MinecraftReflection.getPacketDataSerializer(Unpooled.wrappedBuffer(rawData.toByteArray()));
            CustomPacketPayload payload = new DiscardedPayload(
                    (net.minecraft.resources.MinecraftKey) key, Unpooled.wrappedBuffer(rawData.toByteArray()));
            sendClientBoundCustomPayloadPacket(event.getPlayer(), payload);
        } catch (IOException ignored) {
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
                if (blockData instanceof Comparator) {
                    ((Comparator) blockData).setMode(Comparator.Mode.SUBTRACT);
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
        BlockFace out = null;
        switch (in) {
            case NORTH -> out = BlockFace.EAST;
            case WEST -> out = BlockFace.NORTH;
            case SOUTH -> out = BlockFace.WEST;
            case EAST -> out = BlockFace.SOUTH;
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
        PacketContainer packet = event.getPacket();
        DiscardedPayload payload = (DiscardedPayload) packet.getModifier().read(0);
        if (!(payload.b().a().equals("carpet") && payload.b().b().equals("hello"))) {
            return;
        }
        ByteBuf data = payload.data();
        try {
            DataInputStream in = new DataInputStream(new ByteBufInputStream(data));
            if (StreamSerializer.getDefault().deserializeVarInt(in) != 420) {
                return;
            }
            Object key = MinecraftKey.getConverter().getGeneric(new MinecraftKey("carpet", "hello"));
            NbtCompound abpRule = NbtFactory.ofCompound("Rules", List.of(NbtFactory.of("Value", "true"),
                    NbtFactory.of("Manager", "carpet"),
                    NbtFactory.of("Rule", "accurateBlockPlacement")));
            ByteArrayOutputStream rawData = new ByteArrayOutputStream();
            DataOutputStream outputStream = new DataOutputStream(rawData);
            StreamSerializer.getDefault().serializeVarInt(outputStream, 1);
            StreamSerializer.getDefault().serializeCompound(outputStream, abpRule);
            CustomPacketPayload rulePayload = new DiscardedPayload(
                    (net.minecraft.resources.MinecraftKey) key, Unpooled.wrappedBuffer(rawData.toByteArray()));
            sendClientBoundCustomPayloadPacket(event.getPlayer(), rulePayload);
        } catch (IOException ignored) {
        }
    }

    public static void sendClientBoundCustomPayloadPacket(Player player, CustomPacketPayload payload) {
        // Convert Bukkit Player to ServerPlayer
        CraftPlayer craftPlayer = (CraftPlayer) player;
        craftPlayer.getHandle();
        //ServerPlayer serverPlayer = craftPlayer.getHandle();
        // Create Client boundCustomPayloadPacket
        ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(payload);
        // Send packet to player
        ServerCommonPacketListenerImpl connection = craftPlayer.getHandle().c;
        connection.sendPacket(packet);
    }
}
