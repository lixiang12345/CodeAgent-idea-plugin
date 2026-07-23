<script lang="ts">
  import Icon from "./Icon.svelte";
  import type { QueuedMessage } from "./protocol";

  interface Props {
    messages: QueuedMessage[];
    paused: boolean;
    editingMessageId: string | null;
    onpausechange: (paused: boolean) => void;
    onedit: (message: QueuedMessage) => void;
    onsendnow: (message: QueuedMessage) => void;
    ondelete: (message: QueuedMessage) => void;
    oncancel: () => void;
  }

  let {
    messages,
    paused,
    editingMessageId,
    onpausechange,
    onedit,
    onsendnow,
    ondelete,
    oncancel,
  }: Props = $props();
  let collapsed = $state(false);

  const nextMessage = $derived(messages[0]);
</script>

{#if messages.length > 0}
  <section class="message-queue-panel" class:paused aria-label="Message queue">
    <header>
      <button
        type="button"
        class="message-queue-summary"
        aria-label={collapsed ? "Expand message queue" : "Collapse message queue"}
        aria-expanded={!collapsed}
        onclick={() => collapsed = !collapsed}
      >
        <Icon name="list" size={13} />
        <strong>{messages.length} Queued{paused ? " (Paused)" : ""}</strong>
        {#if collapsed && nextMessage}
          <span class="message-queue-preview"><Icon name="chevron-right" size={11} />{nextMessage.text}</span>
        {/if}
        <Icon name={collapsed ? "chevron-up" : "chevron-down"} size={12} />
      </button>
      <button
        type="button"
        class="message-queue-pause"
        title={paused ? "Resume queue" : "Pause queue"}
        aria-label={paused ? "Resume queue" : "Pause queue"}
        disabled={editingMessageId !== null}
        onclick={() => onpausechange(!paused)}
      >
        <Icon name={paused ? "play" : "pause"} size={12} />
      </button>
    </header>

    {#if !collapsed}
      <div class="message-queue-items" role="list" aria-live="polite">
        {#each messages as message, index (message.id)}
          <div class="message-queue-item" class:editing={editingMessageId === message.id} role="listitem">
            <i>{index + 1}</i>
            <span title={message.text}>{message.text}</span>
            <b>{message.mode}</b>
            <div class="message-queue-actions">
              {#if editingMessageId === message.id}
                <button type="button" title="Cancel edit" aria-label="Cancel queued message edit" onclick={oncancel}><Icon name="x" size={12} /></button>
              {:else}
                <button type="button" title="Edit message" aria-label="Edit queued message" onclick={() => onedit(message)}><Icon name="pencil" size={12} /></button>
                <button type="button" title="Send now" aria-label="Send queued message now" onclick={() => onsendnow(message)}><Icon name="send-horizontal" size={12} /></button>
              {/if}
              <button type="button" title="Delete" aria-label="Delete queued message" onclick={() => ondelete(message)}><Icon name="trash-2" size={12} /></button>
            </div>
          </div>
        {/each}
      </div>
    {/if}
  </section>
{/if}

<style>
  .message-queue-panel {
    max-height: 164px;
    margin-bottom: 5px;
    overflow: hidden;
    border: 1px solid #464b52;
    border-radius: 5px;
    color: #b8bec6;
    background: #222428;
  }

  .message-queue-panel.paused {
    border-color: #5c5139;
  }

  header {
    min-height: 30px;
    display: flex;
    align-items: stretch;
    border-bottom: 1px solid #383c42;
  }

  .message-queue-panel.paused header {
    border-bottom-color: #484131;
  }

  button {
    border: 0;
    color: inherit;
    background: transparent;
    cursor: pointer;
  }

  button:hover,
  button:focus-visible {
    color: #e1e5ea;
    background: rgba(255, 255, 255, .055);
  }

  button:disabled {
    opacity: .38;
    cursor: default;
  }

  .message-queue-summary {
    min-width: 0;
    flex: 1;
    padding: 0 6px;
    display: flex;
    align-items: center;
    gap: 6px;
    color: #8994a1;
    text-align: left;
  }

  .message-queue-summary strong {
    flex: 0 0 auto;
    color: #c9cfd6;
    font-size: 10px;
    font-weight: 550;
    white-space: nowrap;
  }

  .paused .message-queue-summary,
  .paused .message-queue-summary strong {
    color: #d2b776;
  }

  .message-queue-preview {
    min-width: 0;
    flex: 1;
    display: flex;
    align-items: center;
    gap: 2px;
    overflow: hidden;
    color: #818995;
    font-size: 9.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .message-queue-pause {
    width: 29px;
    flex: 0 0 29px;
    display: grid;
    place-items: center;
    border-left: 1px solid #383c42;
    color: #8e969f;
  }

  .paused .message-queue-pause {
    border-left-color: #484131;
    color: #d2b776;
  }

  .message-queue-items {
    max-height: 132px;
    padding: 3px;
    overflow-y: auto;
  }

  .message-queue-item {
    min-height: 27px;
    padding: 2px 4px;
    display: grid;
    grid-template-columns: 14px minmax(0, 1fr) auto auto;
    align-items: center;
    gap: 5px;
    border-radius: 3px;
  }

  .message-queue-item:hover,
  .message-queue-item:focus-within,
  .message-queue-item.editing {
    background: rgba(255, 255, 255, .045);
  }

  .message-queue-item.editing {
    box-shadow: inset 2px 0 #4a7fd4;
  }

  .message-queue-item > i {
    color: #707985;
    font: normal 8.5px var(--mono);
    text-align: center;
  }

  .message-queue-item > span {
    min-width: 0;
    overflow: hidden;
    color: #b9c0c8;
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .message-queue-item > b {
    color: #737c87;
    font: 8px var(--mono);
    text-transform: capitalize;
  }

  .message-queue-actions {
    display: flex;
    align-items: center;
    gap: 1px;
    opacity: 0;
  }

  .message-queue-item:hover .message-queue-actions,
  .message-queue-item:focus-within .message-queue-actions,
  .message-queue-item.editing .message-queue-actions {
    opacity: 1;
  }

  .message-queue-actions button {
    width: 21px;
    height: 21px;
    padding: 0;
    display: grid;
    place-items: center;
    border-radius: 3px;
    color: #858e99;
  }

  @media (hover: none) {
    .message-queue-actions {
      opacity: 1;
    }
  }
</style>
