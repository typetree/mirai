package net.mamoe.mirai.network

import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.bytes.ByteArrayDecoder
import io.netty.handler.codec.bytes.ByteArrayEncoder
import net.mamoe.mirai.network.packet.client.ClientPacket
import net.mamoe.mirai.network.packet.client.login.*
import net.mamoe.mirai.network.packet.client.touch.ClientTouchPacket
import net.mamoe.mirai.network.packet.client.writeHex
import net.mamoe.mirai.network.packet.server.ServerPacket
import net.mamoe.mirai.network.packet.server.login.*
import net.mamoe.mirai.network.packet.server.security.ServerSessionKeyResponsePacket
import net.mamoe.mirai.network.packet.server.security.ServerSessionKeyResponsePacketEncrypted
import net.mamoe.mirai.network.packet.server.touch.ServerTouchResponsePacket
import net.mamoe.mirai.util.getRandomKey
import net.mamoe.mirai.utils.MiraiLogger
import java.net.DatagramPacket
import java.net.InetSocketAddress

/**
 * [number] is a QQ number.
 *
 * @author Him188moe
 */
class Robot(val number: Int, private val password: String) {
    private var sequence: Int = 0

    private var channel: Channel? = null

    private var serverIP: String = ""
        set(value) {
            serverAddress = InetSocketAddress(value, 8000)
        }

    private lateinit var serverAddress: InetSocketAddress;

    private lateinit var token00BA: ByteArray
    private lateinit var token0825: ByteArray
    private var loginTime: Int = 0
    private lateinit var loginIP: String
    private var tgtgtKey: ByteArray? = null

    /**
     * Kind of key, similar to sessionKey
     */
    private lateinit var tlv0105: ByteArray
    private lateinit var sessionKey: ByteArray
    /**
     * Kind of key, similar to sessionKey
     */
    private lateinit var _0828_rec_decr_key: ByteArray

    @ExperimentalUnsignedTypes
    private var md5_32: ByteArray = getRandomKey(32)


    @ExperimentalUnsignedTypes
    private fun onPacketReceived(packet: ServerPacket) {
        packet.decode()
        println(packet.toString())
        when (packet) {
            is ServerTouchResponsePacket -> {
                if (packet.serverIP != null) {//redirection
                    connect(packet.serverIP!!)
                    sendPacket(ClientServerRedirectionPacket(packet.serverIP!!, number))
                } else {//password submission
                    this.loginIP = packet.loginIP
                    this.loginTime = packet.loginTime
                    this.token0825 = packet.token
                    this.tgtgtKey = packet.tgtgtKey
                    sendPacket(ClientPasswordSubmissionPacket(this.number, this.password, packet.loginTime, packet.loginIP, packet.tgtgtKey, packet.token))
                }
            }

            is ServerLoginResponseFailedPacket -> {
                channel = null
                println("Login failed: " + packet.state.toString())
                return
            }

            is ServerLoginResponseVerificationCodePacket -> {
                //[token00BA]可能来自这里
                this.token00BA = packet.token00BA
                if (packet.unknownBoolean != null && packet.unknownBoolean!!) {
                    this.sequence = 1
                    sendPacket(ClientLoginVerificationCodePacket(this.number, this.token0825, this.sequence, this.token00BA))
                }

            }

            is ServerLoginResponseSuccessPacket -> {
                this._0828_rec_decr_key = packet._0828_rec_decr_key
                sendPacket(ClientLoginSucceedConfirmationPacket(this.number, this.serverIP, this.md5_32, packet.token38, packet.token88, packet.encryptionKey, this.tlv0105))
            }

            //这个有可能是客户端发送验证码之后收到的回复验证码是否正确?
            is ServerLoginResponseResendPacket -> {
                if (packet.flag == ServerLoginResponseResendPacket.Flag.`08 36 31 03`) {
                    this.tgtgtKey = packet.tgtgtKey
                    sendPacket(ClientLoginResendPacket3104(this.number, this.password, this.loginTime, this.loginIP, this.tgtgtKey!!, this.token0825, this.token00BA))
                } else {
                    sendPacket(ClientLoginResendPacket3106(this.number, this.password, this.loginTime, this.loginIP, this.tgtgtKey!!, this.token0825, this.token00BA))
                }
            }

            is ServerSessionKeyResponsePacket -> {
                this.sessionKey = packet.sessionKey
                this.tlv0105 = packet.tlv0105
            }

            is ServerLoginResponseResendPacketEncrypted -> onPacketReceived(packet.decrypt(this.tgtgtKey!!))
            is ServerLoginResponseSuccessPacketEncrypted -> onPacketReceived(packet.decrypt(this.tgtgtKey!!))
            is ServerSessionKeyResponsePacketEncrypted -> onPacketReceived(packet.decrypt(this._0828_rec_decr_key))

            else -> throw IllegalStateException()
        }

    }

    @ExperimentalUnsignedTypes
    private fun sendPacket(packet: ClientPacket) {
        try {
            packet.encode()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        packet.writeHex(Protocol.tail)
        println("Packet sent: $packet")
        val p = DatagramPacket(packet.toByteArray());
        p.socketAddress = this.serverAddress
        channel!!.writeAndFlush(p)
    }

    companion object {
        private fun DatagramPacket(toByteArray: ByteArray): DatagramPacket = DatagramPacket(toByteArray, toByteArray.size)
    }

    @ExperimentalUnsignedTypes
    @Throws(InterruptedException::class)
    fun connect(ip: String, port: Int = 8000) {
        this.serverIP = ip;
        val group = NioEventLoopGroup()
        try {
            val b = Bootstrap()

            b.group(group)
                    .channel(NioSocketChannel::class.java)
                    .remoteAddress(InetSocketAddress("0.0.0.0", 62154))
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(object : ChannelInitializer<SocketChannel>() {
                        @Throws(Exception::class)
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addLast(ByteArrayEncoder())
                            ch.pipeline().addLast(ByteArrayDecoder())
                            ch.pipeline().addLast(object : SimpleChannelInboundHandler<ByteArray>() {
                                override fun channelRead0(ctx: ChannelHandlerContext, bytes: ByteArray) {
                                    try {
                                        /*val remaining = Reader.read(bytes);
                                        if (Reader.isPacketAvailable()) {
                                            robot.onPacketReceived(Reader.toServerPacket())
                                            Reader.init()
                                            remaining
                                        }*/
                                        this@Robot.onPacketReceived(ServerPacket.ofByteArray(bytes))
                                    } catch (e: Exception) {
                                        MiraiLogger.catching(e)
                                    }
                                }

                                override fun channelActive(ctx: ChannelHandlerContext) {
                                    println("Successfully connected to server")
                                }

                                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                    MiraiLogger.catching(cause)
                                }
                            })
                        }
                    })

            channel = b.connect().sync().channel()

            sendPacket(ClientTouchPacket(this@Robot.number, serverIP))
            channel!!.closeFuture().sync()
        } finally {
            group.shutdownGracefully().sync()
        }
    }
}