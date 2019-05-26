package com.nukkitx.network.raknet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import javax.annotation.ParametersAreNonnullByDefault;
import java.net.InetSocketAddress;

@ParametersAreNonnullByDefault
public class RakNetServerSession extends RakNetSession {
    private final RakNetServer rakNet;

    RakNetServerSession(RakNetServer rakNet, InetSocketAddress remoteAddress, Channel channel, int mtu) {
        super(remoteAddress, channel, mtu);
        this.rakNet = rakNet;
    }

    @Override
    protected void onPacket(ByteBuf buffer) {
        short packetId = buffer.readUnsignedByte();

        switch (packetId) {
            case RakNetConstants.ID_OPEN_CONNECTION_REQUEST_2:
                this.onOpenConnectionRequest2(buffer);
                break;
            case RakNetConstants.ID_CONNECTION_REQUEST:
                this.onConnectionRequest(buffer);
                break;
            case RakNetConstants.ID_NEW_INCOMING_CONNECTION:
                this.onNewIncomingConnection();
                break;
        }
    }

    @Override
    protected void onClose() {
        if (!this.rakNet.sessionsByAddress.remove(this.address, this)) {
            throw new IllegalStateException("Session was not found in session map");
        }
    }

    @Override
    public RakNet getRakNet() {
        return this.rakNet;
    }

    private void onOpenConnectionRequest2(ByteBuf buffer) {
        if (this.getState() != RakNetState.INITIALIZING) {
            return;
        }

        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return;
        }

        NetworkUtils.readAddress(buffer);

        this.mtu = RakNetUtils.clamp(buffer.readUnsignedShort(), RakNetConstants.MINIMUM_MTU_SIZE,
                RakNetConstants.MAXIMUM_MTU_SIZE);
        this.guid = buffer.readLong();

        // We can now accept RakNet datagrams.
        this.initialize();

        sendOpenConnectionReply2();
        this.setState(RakNetState.INITIALIZED);
    }

    private void onConnectionRequest(ByteBuf buffer) {
        long guid = buffer.readLong();
        long time = buffer.readLong();
        boolean security = buffer.readBoolean();

        if (this.guid != guid || security) {
            this.sendConnectionFailure(RakNetConstants.ID_CONNECTION_REQUEST_FAILED);
            this.close(DisconnectReason.DISCONNECTED);
            return;
        }

        this.setState(RakNetState.CONNECTING);

        this.sendConnectionRequestAccepted(time);
    }

    private void onNewIncomingConnection() {
        if (this.getState() != RakNetState.CONNECTING) {
            return;
        }

        this.setState(RakNetState.CONNECTED);
    }

    void sendOpenConnectionReply1() {
        ByteBuf buffer = this.allocateBuffer(28);

        buffer.writeByte(RakNetConstants.ID_OPEN_CONNECTION_REPLY_1);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.rakNet.guid);
        buffer.writeBoolean(false); // Security
        buffer.writeShort(this.mtu);

        this.sendDirect(buffer);
    }

    private void sendOpenConnectionReply2() {
        ByteBuf buffer = this.allocateBuffer(31);

        buffer.writeByte(RakNetConstants.ID_OPEN_CONNECTION_REPLY_2);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.rakNet.guid);
        NetworkUtils.writeAddress(buffer, this.address);
        buffer.writeShort(this.mtu);
        buffer.writeBoolean(false); // Security

        this.sendDirect(buffer);
    }

    private void sendConnectionFailure(short id) {
        ByteBuf buffer = this.allocateBuffer(21);
        buffer.writeByte(id);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.rakNet.guid);

        this.sendDirect(buffer);
    }

    private void sendConnectionRequestAccepted(long time) {
        ByteBuf buffer;
        boolean ipv6 = this.isIpv6Session();

        if (ipv6) {
            buffer = this.allocateBuffer(626); // 29 bytes per address
        } else {
            buffer = this.allocateBuffer(166); // 7 bytes per address
        }

        buffer.writeByte(RakNetConstants.ID_CONNECTION_REQUEST_ACCEPTED);
        NetworkUtils.writeAddress(buffer, this.address);
        buffer.writeShort(0); // System index

        for (InetSocketAddress socketAddress : ipv6 ? RakNetUtils.LOCAL_IP_ADDRESSES_V6 : RakNetUtils.LOCAL_IP_ADDRESSES_V4) {
            NetworkUtils.writeAddress(buffer, socketAddress);
        }

        buffer.writeLong(time);
        buffer.writeLong(System.currentTimeMillis());

        this.send(buffer, RakNetPriority.IMMEDIATE);
    }
}
