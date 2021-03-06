CREATE  TABLE IF NOT EXISTS tig_pubsub_nodes (
 `service_jid` VARCHAR(2049) NOT NULL  /* Service JID */,
 `service_jid_sha1` CHAR(40) NOT NULL  /* SHA1 hash of service_jid_sha1 */,
 `name` TEXT NOT NULL  /* Node name (unique with service_jid). */ ,
 `name_sha1` CHAR(40) NOT NULL  /* SHA1 hash of node_name used for uniqueness */,
 `type` INT(1) NOT NULL  /* Node type (0:collection, 1:leaf). */ ,
 `title` VARCHAR(1000) NULL  /* A friendly name for the node. */ ,
 `description` TEXT  /* A description of the node. */ ,
 `creator` VARCHAR(2047) NULL  /* The JID of the node creator. */ ,
 `creation_date` DATETIME NULL  /* The datetime when the node was created. */ ,
 `configuration` MEDIUMTEXT NULL ,
 `affiliations` MEDIUMTEXT NULL ,
 PRIMARY KEY (`service_jid_sha1`(40), `name_sha1`(40)),
 INDEX USING HASH (`service_jid`(255)),
 INDEX USING HASH (`name`(255))
)
ENGINE=InnoDB default character set utf8 ROW_FORMAT=DYNAMIC;
/* This node table contains attributes which are common to both node types. */

CREATE  TABLE IF NOT EXISTS tig_pubsub_items (
 `service_jid_sha1` CHAR(40) NOT NULL,
 `node_name_sha1` CHAR(40) NOT NULL,
 `id` TEXT NOT NULL,
 `creation_date` DATETIME NULL,
 `publisher` VARCHAR(2047) NULL,
 `update_date` DATETIME NULL,
 `data` MEDIUMTEXT NULL,
 PRIMARY KEY USING HASH (`service_jid_sha1`(40),`node_name_sha1`(40), `id`(255)),
 INDEX (`id`(255)),
 CONSTRAINT
  FOREIGN KEY (`service_jid_sha1`, `node_name_sha1`)
  REFERENCES `tig_pubsub_nodes`(`service_jid_sha1`, `name_sha1`)
  MATCH FULL
  ON DELETE CASCADE
)
ENGINE=InnoDB default character set utf8 ROW_FORMAT=DYNAMIC;
/* Items stored by persistent nodes. */

CREATE  TABLE IF NOT EXISTS tig_pubsub_subscriptions (
 `service_jid_sha1` CHAR(40) NOT NULL,
 `node_name_sha1` CHAR(40) NOT NULL,
 `index` BIGINT NOT NULL,
 `data` MEDIUMTEXT NULL,
 PRIMARY KEY (`service_jid_sha1`(40),`node_name_sha1`(40),`index`),
 CONSTRAINT
  FOREIGN KEY (`service_jid_sha1`,`node_name_sha1`)
  REFERENCES `tig_pubsub_nodes`(`service_jid_sha1`,`name_sha1`)
  MATCH FULL
  ON DELETE CASCADE
)
ENGINE=InnoDB default character set utf8 ROW_FORMAT=DYNAMIC;
/* Node subscriptions. */
 

delimiter //

-- Create node
drop procedure if exists TigPubSubCreateNode //
create procedure TigPubSubCreateNode(_service_jid varchar(2049), _node_name text, _node_type int(1), _node_creator varchar(2047), _node_conf mediumtext)
begin
  insert into tig_pubsub_nodes (service_jid, service_jid_sha1, name, name_sha1, type, creator, creation_date, configuration)
    values (_service_jid, SHA1(_service_jid), _node_name, SHA1(_node_name), _node_type, _node_creator, now(), _node_conf);
end //

-- Remove node
drop procedure if exists TigPubSubRemoveNode //
create procedure TigPubSubRemoveNode(_service_jid varchar(2049), _node_name text)
begin
  delete from tig_pubsub_items where service_jid_sha1 = SHA1(_service_jid) and node_name_sha1 = SHA1(_node_name);
  delete from tig_pubsub_subscriptions where service_jid_sha1 = SHA1(_service_jid) and node_name_sha1 = SHA1(_node_name);
  delete from tig_pubsub_nodes where service_jid_sha1 = SHA1(_service_jid) and name_sha1 = SHA1(_node_name);
end //

-- Get item of the node
drop procedure if exists TigPubSubGetItem //
create procedure TigPubSubGetItem(_service_jid varchar(2049), _node_name text, _item_id text)
begin
  select data, publisher, creation_date, update_date
    from tig_pubsub_items where service_jid_sha1 = SHA1(_service_jid) AND node_name_sha1 = SHA1(_node_name) AND id = _item_id;
end //

-- Write item of the node
drop procedure if exists TigPubSubWriteItem //
create procedure TigPubSubWriteItem(_service_jid varchar(2049), _node_name text, _item_id text, _publisher varchar(2047), _item_data mediumtext)
begin
  insert into tig_pubsub_items (service_jid_sha1, node_name_sha1, id, creation_date, update_date, publisher, data)
    values (SHA1(_service_jid), SHA1(_node_name), _item_id, now(), now(), _publisher, _item_data)
    on duplicate key update publisher = _publisher, data = _item_data, update_date = now();
end //

-- Delete item
drop procedure if exists TigPubSubDeleteItem //
create procedure TigPubSubDeleteItem(_service_jid varchar(2049), _node_name text, _item_id text)
begin
  delete from tig_pubsub_items where service_jid_sha1 = SHA1(_service_jid) AND node_name_sha1 = SHA1(_node_name) AND id = _item_id ;
end //

-- Get node's item IDs
drop procedure if exists TigPubSubGetNodeItemsIds //
create procedure TigPubSubGetNodeItemsIds(_service_jid varchar(2049), _node_name text)
begin
  select id from tig_pubsub_items where service_jid_sha1 = SHA1(_service_jid) AND node_name_sha1 = SHA1(_node_name) order by creation_date;
end //

-- Get all nodes names
drop procedure if exists TigPubSubGetAllNodes //
create procedure TigPubSubGetAllNodes(_service_jid varchar(2049))
begin
  select name from tig_pubsub_nodes where  service_jid_sha1 = SHA1(_service_jid);
end //

-- Delete all nodes
drop procedure if exists TigPubSubDeleteAllNodes //
create procedure TigPubSubDeleteAllNodes(_service_jid varchar(2049))
begin
  delete from tig_pubsub_items where service_jid_sha1 = SHA1(_service_jid);
  delete from tig_pubsub_subscriptions where service_jid_sha1 = SHA1(_service_jid);
  delete from tig_pubsub_nodes where service_jid_sha1 = SHA1(_service_jid);
end //

-- Set node configuration
drop procedure if exists TigPubSubSetNodeConfiguration //
create procedure TigPubSubSetNodeConfiguration(_service_jid varchar(2049), _node_name text, _node_conf mediumtext)
begin
  update tig_pubsub_nodes set configuration = _node_conf where service_jid_sha1 = SHA1(_service_jid) AND name_sha1 = SHA1(_node_name);
end //

-- Set node affiliations
drop procedure if exists TigPubSubSetNodeAffiliations //
create procedure TigPubSubSetNodeAffiliations(_service_jid varchar(2049), _node_name text, _node_aff mediumtext)
begin
  update tig_pubsub_nodes set affiliations = _node_aff where service_jid_sha1 = SHA1(_service_jid) AND name_sha1 = SHA1(_node_name);
end //

-- Get node configuration
drop procedure if exists TigPubSubGetNodeConfiguration //
create procedure TigPubSubGetNodeConfiguration(_service_jid varchar(2049), _node_name text)
begin
  select configuration from tig_pubsub_nodes where service_jid_sha1 = SHA1(_service_jid) AND name_sha1 = SHA1(_node_name);
end //

-- Get node affiliations
drop procedure if exists TigPubSubGetNodeAffiliations //
create procedure TigPubSubGetNodeAffiliations(_service_jid varchar(2049), _node_name text)
begin
  select affiliations from tig_pubsub_nodes where service_jid_sha1 = SHA1(_service_jid) AND name_sha1 = SHA1(_node_name);
end //

-- Get node subscriptions
drop procedure if exists TigPubSubGetNodeSubscriptions //
create procedure TigPubSubGetNodeSubscriptions(_service_jid varchar(2049), _node_name text)
begin
  select data from tig_pubsub_subscriptions where service_jid_sha1 = SHA1(_service_jid) AND node_name_sha1 = SHA1(_node_name) order by `index` ;
end //

-- Set node subscription
drop procedure if exists TigPubSubSetNodeSubscriptions //
create procedure TigPubSubSetNodeSubscriptions(_service_jid varchar(2049), _node_name text, _node_index bigint, _node_data mediumtext)
begin
  insert into tig_pubsub_subscriptions (service_jid_sha1, node_name_sha1, `index`, data)
    values (SHA1(_service_jid), SHA1(_node_name), _node_index, _node_data)
    on duplicate key update data = _node_data;
end //

-- Delete node subscription
drop procedure if exists TigPubSubDeleteNodeSubscriptions //
create procedure TigPubSubDeleteNodeSubscriptions(_service_jid varchar(2049), _node_name text, _node_index bigint)
begin
  delete from tig_pubsub_subscriptions where service_jid_sha1 = SHA1(_service_jid) AND node_name_sha1 = SHA1(_node_name) AND `index` = _node_index ;
end //


delimiter ;

-- Helper function for calculating number of subscriptions in database
drop function if exists substrCount;
CREATE FUNCTION substrCount(x mediumtext, delim varchar(12)) returns int return (length(x)-length(REPLACE(x, delim, '')))/length(delim);
