package com.herdesk.common;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 获取本机内网 IP 地址。
 */
public final class NetworkAddressUtil {

    private NetworkAddressUtil() {
    }

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
