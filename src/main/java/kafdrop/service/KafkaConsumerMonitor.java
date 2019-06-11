package kafdrop.service;

import kafdrop.model.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.*;
import org.slf4j.*;
import org.springframework.stereotype.*;

import java.util.*;
import java.util.Map.*;
import java.util.function.*;
import java.util.stream.*;

import static java.util.function.Predicate.not;

@Service
public final class KafkaConsumerMonitor implements ConsumerMonitor {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerMonitor.class);

  private final KafkaHighLevelAdminClient highLevelAdminClient;

  public KafkaConsumerMonitor(KafkaHighLevelAdminClient highLevelAdminClient) {
    this.highLevelAdminClient = highLevelAdminClient;
  }

  @Override
  public List<ConsumerVO> getConsumers(TopicVO topic) {
    final var consumerOffsetsList = getConsumerOffsets(topic.getName());
    LOG.debug("consumerOffsetsList: {}", consumerOffsetsList);
    final var consumerVos = new ArrayList<ConsumerVO>(consumerOffsetsList.size());
    for (var consumerOffsets : consumerOffsetsList) {
      final var consumerVo = new ConsumerVO(consumerOffsets.groupId);
      consumerVos.add(consumerVo);
      final var consumerTopicVo = new ConsumerTopicVO(topic.getName());
      consumerVo.addTopic(consumerTopicVo);
      for (var consumerOffset : consumerOffsets.offsets.entrySet()) {
        final var partition = consumerOffset.getKey().partition();
        final var offset = consumerOffset.getValue().offset();
        final var offsetVo = new ConsumerPartitionVO(consumerOffsets.groupId, topic.getName(), partition);
        consumerTopicVo.addOffset(offsetVo);
        offsetVo.setOffset(offset);
        final var topicPartitionVo = topic.getPartition(partition);
        offsetVo.setSize(topicPartitionVo.map(TopicPartitionVO::getSize).orElse(-1L));
        offsetVo.setFirstOffset(topicPartitionVo.map(TopicPartitionVO::getFirstOffset).orElse(-1L));
      }
    }

    return consumerVos;
  }

  private static final class ConsumerGroupOffsets {
    final String groupId;
    final Map<TopicPartition, OffsetAndMetadata> offsets;

    ConsumerGroupOffsets(String groupId, Map<TopicPartition, OffsetAndMetadata> offsets) {
      this.groupId = groupId;
      this.offsets = offsets;
    }

    boolean isEmpty() {
      return offsets.isEmpty();
    }

    ConsumerGroupOffsets forTopic(String topic) {
      final var filteredOffsets = offsets.entrySet().stream()
          .filter(e -> e.getKey().topic().equals(topic))
          .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
      return new ConsumerGroupOffsets(groupId, filteredOffsets);
    }

    @Override
    public String toString() {
      return ConsumerGroupOffsets.class.getSimpleName() + " [groupId=" + groupId + ", offsets=" + offsets + "]";
    }
  }

  private ConsumerGroupOffsets resolveOffsets(String groupId) {
    return new ConsumerGroupOffsets(groupId, highLevelAdminClient.listConsumerGroupOffsets(groupId));
  }

  private List<ConsumerGroupOffsets> getConsumerOffsets(String topic) {
    final var consumerGroups = highLevelAdminClient.listConsumerGroups();
    return consumerGroups.stream()
        .map(this::resolveOffsets)
        .map(offsets -> offsets.forTopic(topic))
        .filter(not(ConsumerGroupOffsets::isEmpty))
        .collect(Collectors.toList());
  }
}
