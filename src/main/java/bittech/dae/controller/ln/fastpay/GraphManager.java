package bittech.dae.controller.ln.fastpay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import bittech.dae.controller.ln.fastpay.Route.GraphChannel;
import bittech.dae.controller.ln.fastpay.Route.GraphNode;
import bittech.lib.commands.ln.channels.DescribeGraphResponse;
import bittech.lib.commands.ln.channels.DescribeGraphResponse.ChannelInGraph;
import bittech.lib.commands.ln.channels.DescribeGraphResponse.NodeInGraph;
import bittech.lib.commands.ln.channels.Hop;
import bittech.lib.utils.Btc;
import bittech.lib.utils.Require;
import bittech.lib.utils.exceptions.StoredException;
import bittech.lib.utils.logs.Log;

public class GraphManager {

	public Map<String, GraphNode> nodes = new HashMap<String, GraphNode>();
	public Set<GraphChannel> bannedChannels = new HashSet<GraphChannel>();

	public void update(DescribeGraphResponse sourceGraph) {

		for (NodeInGraph node : sourceGraph.nodes) {

			GraphNode graphNode = new GraphNode();
			graphNode.id = node.id;
			graphNode.alias = node.alias;
			nodes.put(graphNode.id, graphNode);
		}

		for (ChannelInGraph channel : sourceGraph.channels) {

//			System.out.println(JsonBuilder.build().toJson(channel));

			GraphNode node1 = nodes.get(channel.node1Id);
			if (node1 == null) {
				System.out.println("WARNING: No such node: " + channel.node1Id);
				continue;
			}

			GraphNode node2 = nodes.get(channel.node2Id);

			if (node2 == null) {
				System.out.println("WARNING: No such node: " + channel.node1Id);
				continue;
			}

			{
				GraphChannel ch = new GraphChannel();
				ch.id = channel.id;
				ch.peerId = channel.node2Id;
				ch.feeBaseMsat = channel.node2_policy.fee_base_msat;
				ch.feeRateMilliMsat = channel.node2_policy.fee_rate_milli_msat;
				ch.timeLockDelta = channel.node2_policy.time_lock_delta;
				ch.maxToTransfer = channel.capacitySat;
				node1.channels.add(ch);
			}

			{
				GraphChannel ch = new GraphChannel();
				ch.id = channel.id;
				ch.peerId = channel.node1Id;
				ch.feeBaseMsat = channel.node1_policy.fee_base_msat;
				ch.feeRateMilliMsat = channel.node1_policy.fee_rate_milli_msat;
				ch.timeLockDelta = channel.node1_policy.time_lock_delta;
				ch.maxToTransfer = channel.capacitySat;
				node2.channels.add(ch);
			}

		}

		for (GraphNode node : nodes.values()) {
			Collections.sort(node.channels);
		}
	}
	
	public bittech.lib.commands.ln.channels.Route findRoute(String from, String to, Btc amount, Set<String> excludeChannels) {
		Log log = Log.build().param("from", from).param("to", to).param("amount", amount).param("excludeChannels", excludeChannels);
		log.event("Truing to find route");
		bittech.lib.commands.ln.channels.Route route = prvFindRoute(from, to, amount, excludeChannels);
		log.param("route", route).event("Route found");
		return route;
	}
	
	private bittech.lib.commands.ln.channels.Route prvFindRoute(String from, String to, Btc amount, Set<String> excludeChannels) {
//		Log.build().param("from", from).param("to", to).param("amount", amount).event("Loking for route");
		Route route = null;
		int deph = 4;

		Set<String> nodes1 = new HashSet<String>();
		route = findRoute(from, to, amount.toSatRoundFloor(), new HashSet<String>(), excludeChannels, nodes1, deph);

		if (route != null) {
			route.firstNodId = from;
			return convertRoute(route);
		}

		Set<String> nodes2 = new HashSet<String>();
		route = findRoute(to, from, amount.toSatRoundFloor(), new HashSet<String>(), excludeChannels, nodes2, deph);

		if (route != null) {
			route.firstNodId = from;
			return convertRoute(route);
		}

		// not found directly. Looking for common nodes

		for (String node1 : nodes1) {
			if (nodes2.contains(node1)) {
				Route route1 = findRoute(from, node1, amount.toSatRoundFloor(), new HashSet<String>(), excludeChannels, null, deph);
				Route route2 = findRoute(node1, to, amount.toSatRoundFloor(), new HashSet<String>(), excludeChannels, null, deph);
				Require.notNull(route1, "route1");
				Require.notNull(route2, "route2");
				for (GraphChannel ch : route2.hops) {
					route1.hops.add(ch);
				}
				return convertRoute(route1);
			}
		}

		return null;
	}

	public Route findRoute(String from, String to, long amountLimitSat, Set<String> usedNodes, Set<String> excludedChannels, Set<String> savedNodes,
			int deph) {

//		System.out.println(from);

		if (from.equals(to)) {
			Route route = new Route(Btc.fromSat(amountLimitSat));
			return route; // success
		}

		if (savedNodes != null) {
			savedNodes.add(from);
		}

//		System.out.println("deph: " + deph);
		if (deph <= 0) {
			return null;
		}

		GraphNode node = nodes.get(from);
		
		if(node == null) {
			throw new StoredException("Cannot find 'from' node: " + from, null);
		}

		usedNodes.add(from);

		for (GraphChannel channel : node.channels) {
			if (channel.maxToTransfer < amountLimitSat) {
				usedNodes.remove(from);
				return null; // failed
			}
			if (usedNodes.contains(channel.peerId)) {
				continue;
			}
			if (bannedChannels.contains(channel)) {
				continue;
			}
			if(excludedChannels.contains(channel.id)) {
				continue;
			}
			if(channel.maxToTransfer > 10000000) { // TODODL Temporary for debug purpose!
				continue;
			}
			Route route = findRoute(channel.peerId, to, amountLimitSat, usedNodes, excludedChannels, savedNodes, deph - 1);
			if (route == null) {
				continue;
			}
			route.add(channel);
			return route; // success
		}
		usedNodes.remove(from);
		return null; // failed

	}

	public Route findMostCapacious(String from, String to) {
		Set<String> usedNodes = new HashSet<String>();
		Route route = findMostCapacious(from, to, usedNodes, 10);
		if (route != null) {
			route.firstNodId = from;
		}
		return route;
	}

	public Route findMostCapacious(String from, String to, Set<String> usedNodes, int deph) {
		return null;
	}

	private final bittech.lib.commands.ln.channels.Route convertRoute(Route route) {
//		Log.build().param("route", route).event("Comverting route");
		bittech.lib.commands.ln.channels.Route retRoute = new bittech.lib.commands.ln.channels.Route();
		retRoute.hops = new ArrayList<Hop>(route.hops.size());

		ListIterator<GraphChannel> listIterator = route.hops.listIterator(route.hops.size());

		Btc amountToForward = new Btc(route.amount);

		int index = 0;
		Btc lastFee = new Btc("0");
		while (listIterator.hasPrevious()) {
			GraphChannel channel = listIterator.previous();
			Hop hop = new Hop();
			hop.amountToForward = new Btc(amountToForward);
			hop.channelCapacity = Btc.fromSat(channel.maxToTransfer); // TODO: Change
			hop.channelId = channel.id;
			hop.expiry = 12345;
			hop.pubKey = channel.peerId;
			hop.timeLockDelta = channel.timeLockDelta;
			if (index != 0) {
				hop.fee = Btc.fromMsat(channel.feeBaseMsat + (amountToForward.toMsat() * channel.feeRateMilliMsat) / 1000000);
				lastFee = hop.fee;
				amountToForward = amountToForward.add(lastFee);
			} else {
				hop.fee = new Btc("0");
			}
			retRoute.hops.add(hop);
			index++;
		}
	
		Collections.reverse(retRoute.hops);

		if(retRoute.hops.size() > 0) {
			retRoute.totalAmount = amountToForward; //retRoute.hops.get(0).amountToForward.add(lastFee);
			retRoute.totalFees = retRoute.totalAmount.sub(route.amount);
		} else {
			retRoute.totalFees = new Btc("0");
		}
		
		return retRoute;
	}
	
	
	public static void addExpiry(bittech.lib.commands.ln.channels.Route route, int currentBlock, int receiverExpiry) {
//		int index = route.hops.size() - 1;
		int currentLock = currentBlock + receiverExpiry;
//		route.totalTimeLock = blockHight + htlcDiff * (index + 1);
		
		ListIterator<Hop> listIterator = route.hops.listIterator(route.hops.size());
		
		int i = 0;
		while (listIterator.hasPrevious()) {
			Hop hop = listIterator.previous();
			hop.expiry = currentLock;
			if(i!=0) {
				currentLock += hop.timeLockDelta;
			}
			i++;
		}
		
		route.totalTimeLock = currentLock;

	}
	
	// ----------------------- build channels (may be removed in the future)
	public bittech.lib.commands.ln.channels.Route buildRoute(String firstNode, List<String> channelIds, Btc amount) {
		Route route = new Route(amount);
		GraphNode gn = nodes.get(firstNode);
		if(gn == null) {
			throw new StoredException("Cannot find node '" + firstNode + "' in channels graph", null);
		}
		for(String chId : channelIds) {

			GraphChannel ch = findChannel(gn, chId);
			if(ch == null) {
				throw new StoredException("Channel '" + chId + "' not found in channels graph", null);
			}
			
			route.hops.add(ch);
			
			gn = nodes.get(ch.peerId);
			if(gn == null) {
				throw new StoredException("Cannot find node '" + firstNode + "' in channels graph", null);
			}
		}
		
		return convertRoute(route);
	}
	
	private GraphChannel findChannel(GraphNode gn, String id) {
			for(GraphChannel ch : gn.channels) {
				if(id.equals(ch.id)) {
					return ch;
				}
			}
		return null;
	}

}
