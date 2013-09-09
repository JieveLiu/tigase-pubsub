package tigase.pubsub.repository.cached;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import tigase.pubsub.AbstractNodeConfig;
import tigase.pubsub.NodeType;
import tigase.pubsub.Utils;
import tigase.pubsub.repository.IAffiliations;
import tigase.pubsub.repository.IItems;
import tigase.pubsub.repository.IPubSubDAO;
import tigase.pubsub.repository.IPubSubRepository;
import tigase.pubsub.repository.ISubscriptions;
import tigase.pubsub.repository.PubSubDAO;
import tigase.pubsub.repository.RepositoryException;
import tigase.pubsub.repository.stateless.UsersSubscription;
import tigase.pubsub.utils.FragmentedMap;
import tigase.stats.StatisticsList;
import tigase.xmpp.BareJID;

/**
 * Class description
 * 
 * 
 * @version 5.0.0, 2010.03.27 at 05:20:46 GMT
 * @author Artur Hefczyc <artur.hefczyc@tigase.org>
 */
public class CachedPubSubRepository implements IPubSubRepository {

	private class LazyWriteThread implements Runnable {
		private boolean stop = false;

		/**
		 * Constructs ...
		 * 
		 */
		public LazyWriteThread() {
		}

		/**
		 * Method description
		 * 
		 */
		@Override
		public void run() {
			log.info("Started new LazyWriteThread.");

			while (!stop || (nodesToSave.size() > 0)) {
				Node node = nodesToSave.pollFirst();

				if (node != null) {
					long start = System.currentTimeMillis();

					++repo_writes;

					// Prevent node modifications while it is being written to
					// DB
					synchronized (node) {
						try {
							if (node.isDeleted()) {
								continue;
							}

							if (node.affiliationsNeedsWriting()) {
								dao.updateAffiliations(node.getServiceJid(), node.getName(),
										node.getNodeAffiliations().serialize());
								node.affiliationsSaved();
							}

							if (node.subscriptionsNeedsWriting()) {
								FragmentedMap<String, UsersSubscription> fm = node.getNodeSubscriptions().getFragmentedMap();

								fm.defragment();

								for (Integer deletedIndex : fm.getRemovedFragmentIndexes()) {
									dao.removeSubscriptions(node.getServiceJid(), node.getName(), deletedIndex);
								}

								for (Integer changedIndex : fm.getChangedFragmentIndexes()) {
									Map<String, UsersSubscription> ft = fm.getFragment(changedIndex);

									dao.updateSubscriptions(node.getServiceJid(), node.getName(), changedIndex,
											node.getNodeSubscriptions().serialize(ft));
								}

								fm.cleanChangingLog();
								node.subscriptionsSaved();
							}

							if (node.configNeedsWriting()) {
								dao.updateNodeConfig(node.getServiceJid(), node.getName(),
										node.getNodeConfig().getFormElement().toString());
								node.configSaved();
							}
						} catch (Exception e) {
							log.log(Level.WARNING, "Problem saving pubsub data: ", e);
						}

						// If the node still needs writing to the database put
						// it back to the collection
						if (node.needsWriting()) {
							nodesToSave.add(node);
						}
					}

					long end = System.currentTimeMillis();

					writingTime += (end - start);
				} else {
					if (!stop) {
						try {
							synchronized (nodesToSave) {
								nodesToSave.wait();
							}

							// After awaking sleep for 1 more second to allow
							// for building
							// up the buffer for saving. This improved
							// performance.
							Thread.sleep(1000);
						} catch (InterruptedException ex) {
						}
					}
				}
			}

			log.info("Stopped LazyWriteThread...");
		}

		/**
		 * Method description
		 * 
		 */
		public void stop() {
			log.info("Stopping LazyWriteThread...");
			stop = true;
			wakeup();
		}

		/**
		 * Method description
		 * 
		 */
		public void wakeup() {
			synchronized (nodesToSave) {
				nodesToSave.notify();
			}
		}
	}

	private class NodeComparator implements Comparator<Node> {

		/**
		 * Method description
		 * 
		 * 
		 * @param o1
		 * @param o2
		 * 
		 * @return
		 */
		@Override
		public int compare(Node o1, Node o2) {
			if (o1.getCreationTime() < o2.getCreationTime()) {
				return -1;
			}

			if (o1.getCreationTime() > o2.getCreationTime()) {
				return 1;
			}

			return o1.getName().compareTo(o2.getName());
		}
	}

	private class SizedCache extends LinkedHashMap<String, Node> {
		private static final long serialVersionUID = 1L;

		private int maxCacheSize = 1000;

		/**
		 * Constructs ...
		 * 
		 * 
		 * @param maxSize
		 */
		public SizedCache(int maxSize) {
			super(maxSize, 0.1f, true);
			maxCacheSize = maxSize;
		}

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Node> eldest) {
			return (size() > maxCacheSize) && !eldest.getValue().needsWriting();
		}
	}

	/** Field description */
	public final static long MAX_WRITE_DELAY = 1000l * 15l;
	private final PubSubDAO dao;
	protected Logger log = Logger.getLogger(this.getClass().getName());
	private final Integer maxCacheSize;
	// private final Object mutex = new Object();
	private final Map<String, Node> nodes;
	private long nodes_added = 0;
	private final ConcurrentSkipListSet<Node> nodesToSave = new ConcurrentSkipListSet<Node>(new NodeComparator());

	private long repo_writes = 0;

	private final Set<String> rootCollection = new HashSet<String>();
	private LazyWriteThread tlazyWriteThread;

	// private final Object writeThreadMutex = new Object();

	private long updateSubscriptionsCalled = 0;

	private long writingTime = 0;

	/**
	 * Constructs ...
	 * 
	 * 
	 * @param dao
	 * @param maxCacheSize
	 */
	public CachedPubSubRepository(final PubSubDAO dao, final Integer maxCacheSize) {
		this.dao = dao;
		this.maxCacheSize = maxCacheSize;
		nodes = Collections.synchronizedMap(new SizedCache(this.maxCacheSize));

		// Runtime.getRuntime().addShutdownHook(makeLazyWriteThread(true));
		log.config("Initializing Cached Repository with cache size = " + ((maxCacheSize == null) ? "OFF" : maxCacheSize));
		tlazyWriteThread = makeLazyWriteThread(false);

		Thread x = new Thread(tlazyWriteThread);

		x.setName("PubSub-DataWriter");
		x.setDaemon(true);
		x.start();

		// Thread.dumpStack();
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param name
	 * @param stats
	 */
	public void addStats(final String name, final StatisticsList stats) {
		if (this.nodes.size() > 0) {
			stats.add(name, "Cached nodes", this.nodes.size(), Level.FINE);
		} else {
			stats.add(name, "Cached nodes", this.nodes.size(), Level.FINEST);
		}

		if (this.nodesToSave.size() > 0) {
			stats.add(name, "Unsaved nodes", this.nodesToSave.size(), Level.INFO);
		} else {
			stats.add(name, "Unsaved nodes", this.nodesToSave.size(), Level.FINEST);
		}

		long subscriptionsCount = 0;
		long affiliationsCount = 0;

		// synchronized (mutex) {
		Map<String, Node> tmp = null;

		synchronized (nodes) {
			tmp = new LinkedHashMap<String, Node>(nodes);
		}

		for (Node nd : tmp.values()) {
			subscriptionsCount += nd.getNodeSubscriptions().getSubscriptionsMap().size();
			affiliationsCount += nd.getNodeAffiliations().getAffiliationsMap().size();
		}

		// }

		if (updateSubscriptionsCalled > 0) {
			stats.add(name, "Update subscriptions calls", updateSubscriptionsCalled, Level.FINE);
		} else {
			stats.add(name, "Update subscriptions calls", updateSubscriptionsCalled, Level.FINEST);
		}

		if (subscriptionsCount > 0) {
			stats.add(name, "Subscriptions count (in cache)", subscriptionsCount, Level.FINE);
		} else {
			stats.add(name, "Subscriptions count (in cache)", subscriptionsCount, Level.FINEST);
		}

		if (affiliationsCount > 0) {
			stats.add(name, "Affiliations count (in cache)", affiliationsCount, Level.FINE);
		} else {
			stats.add(name, "Affiliations count (in cache)", affiliationsCount, Level.FINEST);
		}

		if (repo_writes > 0) {
			stats.add(name, "Repository writes", repo_writes, Level.FINE);
		} else {
			stats.add(name, "Repository writes", repo_writes, Level.FINEST);
		}

		if (nodes_added > 0) {
			stats.add(name, "Added new nodes", nodes_added, Level.INFO);
		} else {
			stats.add(name, "Added new nodes", nodes_added, Level.FINEST);
		}

		if (nodes_added > 0) {
			stats.add(name, "Total writing time", Utils.longToTime(writingTime), Level.INFO);
		} else {
			stats.add(name, "Total writing time", Utils.longToTime(writingTime), Level.FINEST);
		}

		if (nodes_added + repo_writes > 0) {
			if (nodes_added > 0) {
				stats.add(name, "Average DB write time [ms]", (writingTime / (nodes_added + repo_writes)), Level.INFO);
			} else {
				stats.add(name, "Average DB write time [ms]", (writingTime / (nodes_added + repo_writes)), Level.FINEST);
			}
		}
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public void addToRootCollection(BareJID serviceJid, String nodeName) throws RepositoryException {
		this.dao.addToRootCollection(serviceJid, nodeName);
		this.rootCollection.add(nodeName);
	}

	private String createKey(BareJID serviceJid, String nodeName) {
		return serviceJid.toString() + "/" + nodeName;
	}

	// public void doLazyWrite() {
	// synchronized (writeThreadMutex) {
	// if (tlazyWriteThread == null) {
	// tlazyWriteThread = makeLazyWriteThread(false);
	// Thread x = new Thread(tlazyWriteThread);
	// x.setName("PubSub-DataWriter");
	// x.start();
	// }
	// }
	// }

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * @param ownerJid
	 * @param nodeConfig
	 * @param nodeType
	 * @param collection
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public void createNode(BareJID serviceJid, String nodeName, String ownerJid, AbstractNodeConfig nodeConfig,
			NodeType nodeType, String collection) throws RepositoryException {
		long start = System.currentTimeMillis();

		this.dao.createNode(serviceJid, nodeName, ownerJid, nodeConfig, nodeType, collection);

		NodeAffiliations nodeAffiliations = new NodeAffiliations(tigase.pubsub.repository.NodeAffiliations.create(null));
		NodeSubscriptions nodeSubscriptions = new NodeSubscriptions(tigase.pubsub.repository.NodeSubscriptions.create());
		Node node = new Node(serviceJid, nodeConfig, nodeAffiliations, nodeSubscriptions);

		String key = createKey(serviceJid, nodeName);
		this.nodes.put(key, node);

		long end = System.currentTimeMillis();

		++nodes_added;
		writingTime += (end - start);
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public void deleteNode(BareJID serviceJid, String nodeName) throws RepositoryException {
		String key = createKey(serviceJid, nodeName);
		Node node = this.nodes.get(key);

		this.dao.deleteNode(serviceJid, nodeName);

		if (node != null) {
			node.setDeleted(true);
		}

		this.nodes.remove(key);
	}

	/**
	 * Method description
	 * 
	 */
	@Override
	public void destroy() {

		// No resources have been allocated by the init, but some resources
		// have been allocated in the contructor....
		tlazyWriteThread.stop();
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public void forgetConfiguration(BareJID serviceJid, String nodeName) throws RepositoryException {
		String key = createKey(serviceJid, nodeName);
		this.nodes.remove(key);
	}

	public Collection<Node> getAllNodes() {
		return Collections.unmodifiableCollection(nodes.values());
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param owner
	 * @param bareJid
	 * 
	 * @return
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public String[] getBuddyGroups(BareJID owner, String bareJid) throws RepositoryException {
		return this.dao.getBuddyGroups(owner, bareJid);
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param owner
	 * @param buddy
	 * 
	 * @return
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public String getBuddySubscription(BareJID owner, String buddy) throws RepositoryException {
		return this.dao.getBuddySubscription(owner, buddy);
	}

	private Node getNode(BareJID serviceJid, String nodeName) throws RepositoryException {
		String key = createKey(serviceJid, nodeName);
		Node node = this.nodes.get(key);

		if (node == null) {
			AbstractNodeConfig nodeConfig = this.dao.getNodeConfig(serviceJid, nodeName);

			if (nodeConfig == null) {
				return null;
			}

			NodeAffiliations nodeAffiliations = new NodeAffiliations(this.dao.getNodeAffiliations(serviceJid, nodeName));
			NodeSubscriptions nodeSubscriptions = new NodeSubscriptions(this.dao.getNodeSubscriptions(serviceJid, nodeName));

			node = new Node(serviceJid, nodeConfig, nodeAffiliations, nodeSubscriptions);

			// if (maxCacheSize != null && this.nodes.size() > maxCacheSize) {
			// Iterator<Entry<String, Node>> it =
			// this.nodes.entrySet().iterator();
			// int count = 0;
			// while (it.hasNext() && count < 10) {
			// Entry<String, Node> e = it.next();
			// if (nodesToSave.contains(e.getValue())) {
			// continue;
			// }
			// count++;
			// it.remove();
			// }
			//
			// }
			this.nodes.put(key, node);
		}

		return node;
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * 
	 * @return
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public IAffiliations getNodeAffiliations(BareJID serviceJid, String nodeName) throws RepositoryException {
		Node node = getNode(serviceJid, nodeName);

		return (node == null) ? null : node.getNodeAffiliations();
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * 
	 * @return
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public AbstractNodeConfig getNodeConfig(BareJID serviceJid, String nodeName) throws RepositoryException {
		Node node = getNode(serviceJid, nodeName);

		try {
			return (node == null) ? null : node.getNodeConfig().clone();
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();

			return null;
		}
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * 
	 * @return
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public IItems getNodeItems(BareJID serviceJid, String nodeName) throws RepositoryException {
		return new Items(serviceJid, nodeName, this.dao);
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * 
	 * @return
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public ISubscriptions getNodeSubscriptions(BareJID serviceJid, String nodeName) throws RepositoryException {
		Node node = getNode(serviceJid, nodeName);

		return (node == null) ? null : node.getNodeSubscriptions();
	}

	/**
	 * Method description
	 * 
	 * 
	 * @return
	 */
	@Override
	public IPubSubDAO getPubSubDAO() {
		return this.dao;
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @return
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public String[] getRootCollection(BareJID serviceJid) throws RepositoryException {
		if (rootCollection.size() == 0) {
			String[] x = dao.getRootNodes(serviceJid);

			if (x != null) {
				for (String string : x) {
					rootCollection.add(string);
				}
			}
		}

		return rootCollection.toArray(new String[rootCollection.size()]);
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param owner
	 * 
	 * @return
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public String[] getUserRoster(BareJID owner) throws RepositoryException {
		return this.dao.getUserRoster(owner);
	}

	/**
	 * Method description
	 * 
	 */
	@Override
	public void init() {
		log.config("Cached PubSubRepository initialising...");
	}

	private LazyWriteThread makeLazyWriteThread(final boolean immediatelly) {

		// Thread.dumpStack();
		return new LazyWriteThread();
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public void removeFromRootCollection(BareJID serviceJid, String nodeName) throws RepositoryException {
		dao.removeFromRootCollection(serviceJid, nodeName);
		rootCollection.remove(nodeName);
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * @param nodeConfig
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public void update(BareJID serviceJid, String nodeName, AbstractNodeConfig nodeConfig) throws RepositoryException {
		Node node = getNode(serviceJid, nodeName);

		if (node != null) {
			node.configCopyFrom(nodeConfig);

			// node.setNodeConfigChangeTimestamp();
			// synchronized (mutex) {
			log.finest("Node '" + nodeName + "' added to lazy write queue (config)");
			nodesToSave.add(node);
			tlazyWriteThread.wakeup();

			// }
		}
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * @param nodeAffiliations
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public void update(BareJID serviceJid, String nodeName, IAffiliations nodeAffiliations) throws RepositoryException {
		if (nodeAffiliations instanceof NodeAffiliations) {
			Node node = getNode(serviceJid, nodeName);

			if (node != null) {
				if (node.getNodeAffiliations() != nodeAffiliations) {
					throw new RuntimeException("INCORRECT");
				}

				node.affiliationsMerge();

				// node.setNodeAffiliationsChangeTimestamp();
				// synchronized (mutex) {
				log.finest("Node '" + nodeName + "' added to lazy write queue (affiliations)");
				nodesToSave.add(node);
				tlazyWriteThread.wakeup();

				// }
			}
		} else {
			throw new RuntimeException("Wrong class");
		}
	}

	/**
	 * Method description
	 * 
	 * 
	 * @param serviceJid
	 * @param nodeName
	 * @param nodeSubscriptions
	 * 
	 * @throws RepositoryException
	 */
	@Override
	public void update(BareJID serviceJid, String nodeName, ISubscriptions nodeSubscriptions) throws RepositoryException {
		++updateSubscriptionsCalled;

		if (nodeSubscriptions instanceof NodeSubscriptions) {
			Node node = getNode(serviceJid, nodeName);

			if (node != null) {
				if (node.getNodeSubscriptions() != nodeSubscriptions) {
					throw new RuntimeException("INCORRECT");
				}

				node.subscriptionsMerge();

				// node.setNodeSubscriptionsChangeTimestamp();
				// synchronized (mutex) {
				log.finest("Node '" + nodeName + "' added to lazy write queue (subscriptions)");
				nodesToSave.add(node);
				tlazyWriteThread.wakeup();

				// }
			}
		} else {
			throw new RuntimeException("Wrong class");
		}
	}
}
