package io.metersphere.api.service;

import io.metersphere.api.jmeter.ExtendedParameter;
import io.metersphere.api.jmeter.dto.MsgDTO;
import io.metersphere.config.KafkaConfig;
import io.metersphere.constants.RunModeConstants;
import io.metersphere.dto.JmeterRunRequestDTO;
import io.metersphere.dto.RequestResult;
import io.metersphere.dto.ResultDTO;
import io.metersphere.utils.JsonUtils;
import io.metersphere.utils.LoggerUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.BeanUtils;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProducerService {
    // 初始化不同地址kafka,每个地址初始化一个线程
    private Map<String, KafkaTemplate> kafkaTemplateMap = new ConcurrentHashMap<>();
    public static final String DEBUG_TOPICS_KEY = "MS-API-DEBUG-KEY";

    public KafkaTemplate init(Map<String, Object> producerProps) {
        try {
            Object serverUrl = producerProps.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
            if (serverUrl != null && kafkaTemplateMap.containsKey(serverUrl.toString())) {
                return kafkaTemplateMap.get(serverUrl);
            } else {
                producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        "org.apache.kafka.common.serialization.StringSerializer");
                producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        "org.apache.kafka.common.serialization.StringSerializer");
                DefaultKafkaProducerFactory pf = new DefaultKafkaProducerFactory<>(producerProps);
                KafkaTemplate kafkaTemplate = new KafkaTemplate(pf, true);
                kafkaTemplateMap.put(serverUrl.toString(), kafkaTemplate);
                return kafkaTemplate;
            }
        } catch (Exception e) {
            LoggerUtil.error(e);
            return null;
        }
    }

    public void send(JmeterRunRequestDTO runRequest, String logMessage) {
        ResultDTO dto = new ResultDTO();
        BeanUtils.copyProperties(runRequest, dto);
        dto.setConsole(logMessage);
        dto.setRequestResults(new LinkedList<>());
        dto.setHasEnded(true);
        this.send(dto, runRequest.getKafkaConfig());
    }

    public void send(String key, String message, Map<String, Object> producerProps) {
        KafkaTemplate kafkaTemplate = this.init(producerProps);
        if (kafkaTemplate != null) {
            kafkaTemplate.send(KafkaConfig.TOPICS, key, message);
        }
    }

    public void sendDebug(String key, MsgDTO dto, Map<String, Object> producerProps) {
        KafkaTemplate kafkaTemplate = this.init(producerProps);
        if (kafkaTemplate != null && producerProps.containsKey(DEBUG_TOPICS_KEY)) {
            kafkaTemplate.send(producerProps.get(DEBUG_TOPICS_KEY).toString(), key, JsonUtils.toJSONString(dto));
        }
    }

    public void send(ResultDTO dto, Map<String, Object> kafkaConfig) {
        try {
            LoggerUtil.info("执行完成开始同步发送KAFKA" + dto.getRequestResults().size(), dto.getReportId());
            this.send(dto.getReportId(), JsonUtils.toJSONString(dto), kafkaConfig);
            LoggerUtil.info("同步发送报告信息到KAFKA完成", dto.getReportId());
        } catch (Exception ex) {
            LoggerUtil.error("KAFKA 推送结果异常", dto.getReportId(), ex);
            // 尝试逐条发送
            if (dto != null && CollectionUtils.isNotEmpty(dto.getRequestResults())) {
                dto.getArbitraryData().put(ExtendedParameter.REPORT_STATUS, dto);
                StringBuffer logMsg = new StringBuffer(dto.getConsole())
                        .append("\n")
                        .append("KAFKA推送结果异常：[" + dto.getReportId() + "]")
                        .append("\n")
                        .append(ex.getMessage());
                dto.setConsole(logMsg.toString());
                dto.getRequestResults().forEach(item -> {
                    if (item != null) {
                        ResultDTO resultDTO = new ResultDTO();
                        BeanUtils.copyProperties(dto, resultDTO);
                        resultDTO.setRequestResults(new LinkedList<RequestResult>() {{
                            this.add(item);
                        }});
                        this.send(dto.getReportId(), JsonUtils.toJSONString(resultDTO), kafkaConfig);
                    }
                });
            }
        }

        if (dto != null && StringUtils.equals(dto.getReportType(), RunModeConstants.SET_REPORT.name())) {
            LoggerUtil.info("处理接口集合报告", dto.getReportId());
        }
    }
}
