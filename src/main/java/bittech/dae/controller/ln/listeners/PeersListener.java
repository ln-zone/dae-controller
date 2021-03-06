package bittech.dae.controller.ln.listeners;

import java.util.ArrayList;
import java.util.List;

import bittech.dae.controller.ln.lnd.LndCommandsExecutor;
import bittech.lib.commands.ln.channels.ListChannelsCommand;
import bittech.lib.commands.ln.channels.ListChannelsResponse.ActiveChannel;
import bittech.lib.commands.ln.channels.ListChannelsResponse.Channel;
import bittech.lib.commands.ln.channels.ListPendingChannelsCommand;
import bittech.lib.commands.ln.channels.ListPendingChannelsResponse.ClosedChannel;
import bittech.lib.commands.ln.channels.ListPendingChannelsResponse.ForceClosedChannel;
import bittech.lib.commands.ln.channels.ListPendingChannelsResponse.PendingOpenChannel;
import bittech.lib.commands.ln.channels.ListPendingChannelsResponse.WaitingCloseChannel;
import bittech.lib.commands.ln.peers.ConnectPeerCommand;
import bittech.lib.commands.ln.peers.ListPeersCommand;
import bittech.lib.commands.ln.peers.ListPeersResponse;
import bittech.lib.commands.ln.peers.ListPeersWithChannelsCommand;
import bittech.lib.commands.ln.peers.ListPeersWithChannelsResponse;
import bittech.lib.commands.ln.peers.ListPeersWithChannelsResponse.Peer;
import bittech.lib.protocol.Command;
import bittech.lib.protocol.Listener;
import bittech.lib.protocol.common.NoDataResponse;
import bittech.lib.utils.Require;
import bittech.lib.utils.exceptions.StoredException;
import bittech.lib.utils.logs.Log;

public class PeersListener implements Listener {

	private final LndCommandsExecutor executor;

	public PeersListener(final LndCommandsExecutor executor) {
		this.executor = Require.notNull(executor, "executor");
	}

	@Override
	public Class<?>[] getListeningCommands() {
		return new Class<?>[] { ConnectPeerCommand.class, ListPeersCommand.class, ListPeersWithChannelsCommand.class };
	}

	@Override
	public String[] getListeningServices() {
		return null;
	}

	@Override
	public void commandReceived(String fromServiceName, Command<?, ?> command) throws StoredException {
		if (command instanceof ListPeersWithChannelsCommand) {
			executeListPeersCommand((ListPeersWithChannelsCommand) command);
		} else if (command instanceof ConnectPeerCommand) {
			ConnectPeerCommand cmd = (ConnectPeerCommand) command;

			ConnectPeerCommand cmd2 = new ConnectPeerCommand(cmd.getRequest().uri);
			try {
				Log.build().event("1");
				executor.execute(cmd2);
				Log.build().event("2");
			} catch (Exception ex) {
				Log.build().event("3");
				if (cmd2.getError() != null) {
					if (cmd2.getError().containsMessage("already connected to peer")) {
						Log.build().event("replaceing");
						cmd.response = new NoDataResponse();
						Log.build().event("4");
					} else {
						cmd.error = cmd2.getError();
						Log.build().event("5");
					}
					Log.build().event("6");
					return;
				}
			}
			Log.build().event("7");
			cmd.response = new NoDataResponse();
			Log.build().event("8");
		} else {
			executor.execute(command);
		}
	}

	private void executeListPeersCommand(ListPeersWithChannelsCommand cmd) {
		ListPeersCommand listPeersCmd = new ListPeersCommand();
		executor.execute(listPeersCmd);
		if (listPeersCmd.getError() != null) {
			throw new StoredException("Executeing ListPeersWithChannelsCommand failed",
					new Exception("Executing ListPeersCommand failed", listPeersCmd.getError().toException()));
		}

		ListChannelsCommand listChannelsCmd = new ListChannelsCommand();
		executor.execute(listChannelsCmd);
		if (listChannelsCmd.getError() != null) {
			throw new StoredException("Executeing ListPeersWithChannelsCommand failed",
					new Exception("Executing ListChannelsCommand failed", listChannelsCmd.getError().toException()));
		}

		ListPendingChannelsCommand listPendingChannelsCmd = new ListPendingChannelsCommand();
		executor.execute(listPendingChannelsCmd);
		if (listPendingChannelsCmd.getError() != null) {
			throw new StoredException("Executeing ListPeersWithChannelsCommand failed", new Exception(
					"Executing ListPendingChannelsCommand failed", listPendingChannelsCmd.getError().toException()));
		}

		cmd.response = new ListPeersWithChannelsResponse();
		cmd.response.peers = new ArrayList<Peer>(listPeersCmd.getResponse().peers.size());
		for (ListPeersResponse.Peer origPeer : listPeersCmd.getResponse().peers) {
			Peer peer = new Peer();
			peer.id = origPeer.id;
			peer.address = origPeer.address;
			peer.bytesSent = origPeer.bytesSent;
			peer.bytesReceived = origPeer.bytesReceived;
			peer.sentAmount = origPeer.sentAmount;
			peer.receivedAmount = origPeer.receivedAmount;
			peer.inbound = origPeer.inbound;
			peer.pingTime = origPeer.pingTime;

			peer.pending_open_channels = new ArrayList<PendingOpenChannel>();
			copy(peer.id, listPendingChannelsCmd.getResponse().pending_open_channels, peer.pending_open_channels);

			peer.openedChannels = new ArrayList<ActiveChannel>();
			copy(peer.id, listChannelsCmd.getResponse().channels, peer.openedChannels);
			peer.pending_closing_channels = new ArrayList<ClosedChannel>();
			copy(peer.id, listPendingChannelsCmd.getResponse().pending_closing_channels, peer.pending_closing_channels);
			peer.pending_force_closing_channels = new ArrayList<ForceClosedChannel>();
			copy(peer.id, listPendingChannelsCmd.getResponse().pending_force_closing_channels,
					peer.pending_force_closing_channels);
			peer.waiting_close_channels = new ArrayList<WaitingCloseChannel>();
			copy(peer.id, listPendingChannelsCmd.getResponse().waiting_close_channels, peer.waiting_close_channels);

			cmd.response.peers.add(peer);
		}

	}

	private <T extends Channel> void copy(String nodId, List<T> listFrom, List<T> listTo) {
		if (listFrom == listTo) {
			throw new StoredException("Cannot copy lists. ListFrom and listTo are the same object!", null);
		}
		for (T ch : listFrom) {
			if (nodId.equals(ch.remote_node_pub)) {
				listTo.add(ch);
			}
		}
	}

	@Override
	public void responseSent(String serviceName, Command<?, ?> command) {
		// Nothing here
	}

}
