/*
 * Tigase Jabber/XMPP Publish Subscribe Component
 * Copyright (C) 2007 "Bartosz M. Małkowski" <bartosz.malkowski@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.pubsub.modules;

import java.util.ArrayList;
import java.util.List;

import tigase.criteria.Criteria;
import tigase.criteria.ElementCriteria;
import tigase.pubsub.AbstractModule;
import tigase.pubsub.AbstractNodeConfig;
import tigase.pubsub.Affiliation;
import tigase.pubsub.PubSubConfig;
import tigase.pubsub.exceptions.PubSubErrorCondition;
import tigase.pubsub.exceptions.PubSubException;
import tigase.pubsub.repository.IAffiliations;
import tigase.pubsub.repository.IPubSubRepository;
import tigase.pubsub.repository.RepositoryException;
import tigase.pubsub.repository.inmemory.NodeAffiliation;
import tigase.util.JIDUtils;
import tigase.xml.Element;
import tigase.xmpp.Authorization;

public class ManageAffiliationsModule extends AbstractModule {

	private static final Criteria CRIT = ElementCriteria.name("iq").add(
			ElementCriteria.name("pubsub", "http://jabber.org/protocol/pubsub#owner")).add(ElementCriteria.name("affiliations"));

	private static Element createAffiliationNotification(String fromJid, String toJid, String nodeName, Affiliation affilation) {
		Element message = new Element("message", new String[] { "from", "to" }, new String[] { fromJid, toJid });
		Element pubsub = new Element("pubsub", new String[] { "xmlns" }, new String[] { "http://jabber.org/protocol/pubsub" });
		message.addChild(pubsub);
		Element affilations = new Element("affiliations", new String[] { "node" }, new String[] { nodeName });
		pubsub.addChild(affilations);
		affilations.addChild(new Element("affilation", new String[] { "jid", "affiliation" }, new String[] { toJid,
				affilation.name() }));
		return message;
	}

	public ManageAffiliationsModule(PubSubConfig config, IPubSubRepository pubsubRepository) {
		super(config, pubsubRepository);
	}

	@Override
	public String[] getFeatures() {
		return new String[] { "http://jabber.org/protocol/pubsub#modify-affiliations" };
	}

	@Override
	public Criteria getModuleCriteria() {
		return CRIT;
	}

	@Override
	public List<Element> process(Element element) throws PubSubException {
		try {
			Element pubsub = element.getChild("pubsub", "http://jabber.org/protocol/pubsub#owner");
			Element affiliations = pubsub.getChild("affiliations");
			String nodeName = affiliations.getAttribute("node");
			String type = element.getAttribute("type");

			if (type == null || !type.equals("get") && !type.equals("set")) {
				throw new PubSubException(Authorization.BAD_REQUEST);
			}

			if (nodeName == null) {
				throw new PubSubException(Authorization.BAD_REQUEST, PubSubErrorCondition.NODE_REQUIRED);
			}
			final AbstractNodeConfig nodeConfig = this.repository.getNodeConfig(nodeName);
			if (nodeConfig == null) {
				throw new PubSubException(Authorization.ITEM_NOT_FOUND);
			}
			final IAffiliations nodeAffiliations = this.repository.getNodeAffiliations(nodeName);
			String senderJid = element.getAttribute("from");

			if (!this.config.isAdmin(JIDUtils.getNodeID(senderJid))) {
				NodeAffiliation senderAffiliation = nodeAffiliations.getSubscriberAffiliation(senderJid);
				if (senderAffiliation.getAffiliation() != Affiliation.owner) {
					throw new PubSubException(element, Authorization.FORBIDDEN);
				}
			}

			List<Element> result = new ArrayList<Element>();
			if (type.equals("get")) {
				result = processGet(element, affiliations, nodeName, nodeAffiliations);
			} else if (type.equals("set")) {
				result = processSet(element, affiliations, nodeName, nodeAffiliations);
			}
			if (nodeAffiliations.isChanged()) {
				repository.update(nodeName, nodeAffiliations);
			}
			return result;
		} catch (PubSubException e1) {
			throw e1;
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private List<Element> processGet(Element element, Element affiliations, String nodeName, final IAffiliations nodeAffiliations)
			throws RepositoryException {
		List<Element> result = new ArrayList<Element>();
		Element iq = createResultIQ(element);
		Element ps = new Element("pubsub", new String[] { "xmlns" }, new String[] { "http://jabber.org/protocol/pubsub#owner" });
		iq.addChild(ps);
		Element afr = new Element("affiliations", new String[] { "node" }, new String[] { nodeName });
		ps.addChild(afr);

		NodeAffiliation[] affiliationsList = nodeAffiliations.getAffiliations();
		if (affiliationsList != null) {
			for (NodeAffiliation affi : affiliationsList) {
				if (affi.getAffiliation() == Affiliation.none) {
					continue;
				}
				Element affiliation = new Element("affiliation", new String[] { "jid", "affiliation" }, new String[] {
						affi.getJid(), affi.getAffiliation().name() });
				afr.addChild(affiliation);
			}
		}

		result.add(iq);
		return result;
	}

	private List<Element> processSet(final Element element, final Element affiliations, final String nodeName,
			final IAffiliations nodeAffiliations) throws PubSubException, RepositoryException {
		List<Element> result = new ArrayList<Element>();
		Element iq = createResultIQ(element);
		result.add(iq);
		List<Element> affs = affiliations.getChildren();
		for (Element a : affs) {
			if (!"affiliation".equals(a.getName()))
				throw new PubSubException(Authorization.BAD_REQUEST);
		}
		for (Element af : affs) {
			String strAfiliation = af.getAttribute("affiliation");
			String jid = af.getAttribute("jid");
			if (strAfiliation == null)
				continue;
			Affiliation newAffiliation = Affiliation.valueOf(strAfiliation);
			Affiliation oldAffiliation = nodeAffiliations.getSubscriberAffiliation(jid).getAffiliation();
			oldAffiliation = oldAffiliation == null ? Affiliation.none : oldAffiliation;

			if (oldAffiliation == Affiliation.none && newAffiliation != Affiliation.none) {
				nodeAffiliations.addAffiliation(jid, newAffiliation);
				result.add(createAffiliationNotification(element.getAttribute("to"), jid, nodeName, newAffiliation));
			} else {
				nodeAffiliations.changeAffiliation(jid, newAffiliation);
				result.add(createAffiliationNotification(element.getAttribute("to"), jid, nodeName, newAffiliation));
			}

		}
		return result;
	}
}
