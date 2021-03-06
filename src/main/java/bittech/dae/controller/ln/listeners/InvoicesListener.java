package bittech.dae.controller.ln.listeners;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import bittech.dae.controller.ln.lnd.LndCommandsExecutor;
import bittech.lib.commands.ln.invoices.AddInvoiceCommand;
import bittech.lib.commands.ln.invoices.DecodeInvoiceCommand;
import bittech.lib.commands.ln.invoices.ListInvoicesCommand;
import bittech.lib.commands.ln.invoices.PayInvoiceCommand;
import bittech.lib.commands.ln.invoices.PaymentReceivedCommand;
import bittech.lib.commands.ln.invoices.PaymentReceivedRequest;
import bittech.lib.commands.ln.invoices.RegisterPaymentsListenerCommand;
import bittech.lib.protocol.Command;
import bittech.lib.protocol.Listener;
import bittech.lib.protocol.Node;
import bittech.lib.protocol.common.NoDataResponse;
import bittech.lib.protocol.helpers.CommandBroadcaster;
import bittech.lib.utils.Btc;
import bittech.lib.utils.Require;
import bittech.lib.utils.exceptions.StoredException;
import bittech.lib.utils.logs.Log;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import lnrpc.LightningGrpc;
import lnrpc.Rpc;
import lnrpc.Rpc.Invoice;

public class InvoicesListener implements Listener {

	private final ManagedChannel channel;
	private final LndCommandsExecutor executor;

	private final CommandBroadcaster commandPaymentRecaivedBroadcaster;

	private final Map<Long, String> invoicesLabels = new HashMap<Long, String>();

	public InvoicesListener(Node node, ManagedChannel channel) {
		this.commandPaymentRecaivedBroadcaster = new CommandBroadcaster(Require.notNull(node, "node"),
				"paymentsRegisteredServices.json");
		this.channel = Require.notNull(channel, "channel");
		this.executor = new LndCommandsExecutor(channel);

		subscribeInvoice();
	}

	private void subscribeInvoice() {

		LightningGrpc.LightningStub blockingStub = LightningGrpc.newStub(channel);

		Rpc.InvoiceSubscription request = Rpc.InvoiceSubscription.newBuilder().build();
		blockingStub.subscribeInvoices(request, new StreamObserver<Invoice>() {

			@Override
			public void onNext(Invoice invoice) {

				Log log = Log.build().param("invoice", invoice);
				log.event("onNext 1");
				
				if (invoice.getSettleIndex() == 0) {
					log.event("onNext 2");
					return; // Not paid. Probably added invoice.
				}
				log.event("onNext 3");
				PaymentReceivedRequest req = new PaymentReceivedRequest();
				req.label = invoicesLabels.get(invoice.getAddIndex());
				req.index = invoice.getAddIndex();
				req.amount = Btc.fromSat(invoice.getValue());
				req.amount_received = Btc.fromMsat(invoice.getAmtPaidMsat());
				req.pay_index = invoice.getSettleIndex();
				req.payment_hash = Base64.getEncoder().encodeToString(invoice.getRPreimage().toByteArray());
				req.status = "paid";

				log.event("onNext 4");
				PaymentReceivedCommand paymentReceivedCommand = new PaymentReceivedCommand(req);

				log.event("onNext 5");
				commandPaymentRecaivedBroadcaster.broadcast(paymentReceivedCommand);
				log.event("onNext 6");
			}

			@Override
			public void onError(Throwable t) {
				new StoredException("On error received for invoice", t);
			}

			@Override
			public void onCompleted() {
				new StoredException("Invoice. On completed", null);
			}

		});

	}

	@Override
	public Class<?>[] getListeningCommands() {
		return new Class<?>[] { RegisterPaymentsListenerCommand.class, AddInvoiceCommand.class,
				DecodeInvoiceCommand.class, PayInvoiceCommand.class, ListInvoicesCommand.class };
	}

	@Override
	public String[] getListeningServices() {
		return null;
	}

	@Override
	public void commandReceived(String fromServiceName, Command<?, ?> command) throws StoredException {
		if (command instanceof RegisterPaymentsListenerCommand) {
			RegisterPaymentsListenerCommand cmd = (RegisterPaymentsListenerCommand) command;
			commandPaymentRecaivedBroadcaster.addService(fromServiceName);
			cmd.response = new NoDataResponse();

		} else if (command instanceof AddInvoiceCommand) {
			System.out.println("Adding invoice");
			AddInvoiceCommand cmd = (AddInvoiceCommand) command;
			String label = cmd.getRequest().label;
			if(StringUtils.isEmpty(label)) {
				throw new StoredException("Cannot add invoice without label", null);
			}
			executor.execute(cmd);
			if(cmd.getError() == null) {
				invoicesLabels.put(cmd.getResponse().add_index, label);
			}
			System.out.println("Invoice added");

		} else {
			executor.execute(command);
		}
	}

	@Override
	public void responseSent(String serviceName, Command<?, ?> command) {
		// Nothing here
	}

}
