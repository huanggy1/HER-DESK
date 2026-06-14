package com.herdesk.common;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 获取本机内网 IPv4 地址。
 */
public final class NetworkAddressUtil {

    private NetworkAddressUtil() {
    }

    /**
     * 枚举所有已启用、非回环网卡的 IPv4 地址。
     * <p>
     * 枚举失败或无结果时回退为 {@code 127.0.0.1}，结果按字典序排序。
     */
    public static List<String> getLocalIpv4Addresses() {
        List<String> addresses = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举网卡失败时回退到 localhost
        }
        if (addresses.isEmpty()) {
            addresses.add("127.0.0.1");
        }
        Collections.sort(addresses);
        return addresses;
    }

    /**
     * 返回首个非 127 开头的 IPv4；若无则返回列表首项。
     */
    public static String getPrimaryLocalIpv4() {
        List<String> addresses = getLocalIpv4Addresses();
        for (String address : addresses) {
            if (!address.startsWith("127.")) {
                return address;
            }
        }
        return addresses.get(0);
    }
}
