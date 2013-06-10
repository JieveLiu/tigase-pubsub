/*
 * JabberVersionModule.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2012 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 */



package tigase.pubsub.modules;

//~--- non-JDK imports --------------------------------------------------------

import tigase.criteria.Criteria;
import tigase.criteria.ElementCriteria;

import tigase.pubsub.ElementWriter;
import tigase.pubsub.exceptions.PubSubException;
import tigase.pubsub.Module;
import tigase.pubsub.PubSubVersion;

import tigase.xml.Element;

//~--- JDK imports ------------------------------------------------------------

import java.util.ArrayList;
import java.util.List;
import tigase.pubsub.PacketWriter;
import tigase.server.Packet;

/**
 * Class description
 *
 *
 * @version        Enter version here..., 13/02/20
 * @author         Enter your name here...
 */
public class JabberVersionModule
				implements Module {
	private static final Criteria CRIT = ElementCriteria.nameType("iq",
																				 "get").add(ElementCriteria.name("query",
																					 "jabber:iq:version"));

	//~--- get methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @return
	 */
	@Override
	public String[] getFeatures() {
		return null;
	}

	/**
	 * Method description
	 *
	 *
	 * @return
	 */
	@Override
	public Criteria getModuleCriteria() {
		return CRIT;
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param packet
	 * @param packetWriter
	 *
	 * @return
	 *
	 * @throws PubSubException
	 */
	@Override
	public List<Packet> process(Packet packet, PacketWriter packetWriter)
					throws PubSubException {
		List<Packet> result = new ArrayList<Packet>();
		Element query = new Element("query", new String[] { "xmlns" },
																new String[] { "jabber:iq:version" });

		query.addChild(new Element("name", "Tigase PubSub"));
		query.addChild(new Element("version", PubSubVersion.getVersion()));
		query.addChild(new Element("os",
															 System.getProperty("os.name") + "-" +
															 System.getProperty("os.arch") + "-" +
															 System.getProperty("os.version") + ", " +
															 System.getProperty("java.vm.name") + "-" +
															 System.getProperty("java.version") + " " +
															 System.getProperty("java.vm.vendor")));
		result.add(packet.okResult(query, 0));
		
		return result;
	}
}


//~ Formatted in Tigase Code Convention on 13/02/20
