/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot;

import java.util.List;

import org.connectbot.bean.HostBean;
import org.connectbot.bean.PortForwardBean;
import org.connectbot.service.TerminalBridge;
import org.connectbot.service.TerminalManager;
import org.connectbot.util.HostDatabase;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.database.SQLException;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.VisibleForTesting;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * List all portForwards for a particular host and provide a way for users to add more portForwards,
 * edit existing portForwards, and delete portForwards.
 *
 * @author Kenny Root
 */
public class PortForwardListActivity extends AppCompatListActivity {
	public final static String TAG = "CB.PortForwardListAct";

	private static final int LISTENER_CYCLE_TIME = 500;

	protected HostDatabase hostdb;

	private List<PortForwardBean> portForwards;

	private ServiceConnection connection = null;
	protected TerminalBridge hostBridge = null;
	protected LayoutInflater inflater = null;

	private HostBean host;

	@Override
	public void onStart() {
		super.onStart();

		this.bindService(new Intent(this, TerminalManager.class), connection, Context.BIND_AUTO_CREATE);

		hostdb = HostDatabase.get(this);
	}

	@Override
	public void onStop() {
		super.onStop();

		this.unbindService(connection);

		hostdb = null;
	}

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);

		long hostId = this.getIntent().getLongExtra(Intent.EXTRA_TITLE, -1);

		setContentView(R.layout.act_portforwardlist);

		mListView = (RecyclerView) findViewById(R.id.list);
		mListView.setHasFixedSize(true);
		mListView.setLayoutManager(new LinearLayoutManager(this));
		mListView.addItemDecoration(new ListItemDecoration(this));

		mEmptyView = findViewById(R.id.empty);

		// connect with hosts database and populate list
		this.hostdb = HostDatabase.get(this);
		host = hostdb.findHostById(hostId);

		{
			final String nickname = host != null ? host.getNickname() : null;
			final Resources resources = getResources();

			if (nickname != null) {
				this.setTitle(String.format("%s (%s)",
						resources.getText(R.string.title_port_forwards_list),
						nickname));
			}
		}

		connection = new ServiceConnection() {
			public void onServiceConnected(ComponentName className, IBinder service) {
				TerminalManager bound = ((TerminalManager.TerminalBinder) service).getService();

				hostBridge = bound.getConnectedBridge(host);
				updateHandler.sendEmptyMessage(-1);
			}

			public void onServiceDisconnected(ComponentName name) {
				hostBridge = null;
			}
		};

		this.updateList();

		this.registerForContextMenu(mListView);

		this.inflater = LayoutInflater.from(this);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);

		MenuItem add = menu.add(R.string.portforward_menu_add);
		add.setIcon(android.R.drawable.ic_menu_add);
		add.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			public boolean onMenuItemClick(MenuItem item) {
				// build dialog to prompt user about updating
				final View portForwardView = inflater.inflate(R.layout.dia_portforward, null, false);
				final EditText destEdit = (EditText) portForwardView.findViewById(R.id.portforward_destination);
				final Spinner typeSpinner = (Spinner) portForwardView.findViewById(R.id.portforward_type);

				typeSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
					public void onItemSelected(AdapterView<?> value, View view,
							int position, long id) {
						destEdit.setEnabled(position != 2);
					}
					public void onNothingSelected(AdapterView<?> arg0) {
					}
				});

				new AlertDialog.Builder(PortForwardListActivity.this)
					.setView(portForwardView)
					.setPositiveButton(R.string.portforward_pos, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							try {
								final EditText nicknameEdit = (EditText) portForwardView.findViewById(R.id.nickname);
								final EditText sourcePortEdit = (EditText) portForwardView.findViewById(R.id.portforward_source);

								String type = HostDatabase.PORTFORWARD_LOCAL;
								switch (typeSpinner.getSelectedItemPosition()) {
								case 0:
									type = HostDatabase.PORTFORWARD_LOCAL;
									break;
								case 1:
									type = HostDatabase.PORTFORWARD_REMOTE;
									break;
								case 2:
									type = HostDatabase.PORTFORWARD_DYNAMIC5;
									break;
								}

								PortForwardBean portForward = new PortForwardBean(
										host != null ? host.getId() : -1,
										nicknameEdit.getText().toString(), type,
										sourcePortEdit.getText().toString(),
										destEdit.getText().toString());

								if (hostBridge != null) {
									hostBridge.addPortForward(portForward);
									hostBridge.enablePortForward(portForward);
								}

								if (host != null && !hostdb.savePortForward(portForward)) {
									throw new SQLException("Could not save port forward");
								}

								updateHandler.sendEmptyMessage(-1);
							} catch (Exception e) {
								Log.e(TAG, "Could not update port forward", e);
								// TODO Show failure dialog.
							}
						}
					})
					.setNegativeButton(R.string.delete_neg, null).create().show();

				return true;
			}
		});

		return true;
	}

	protected Handler updateHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			PortForwardListActivity.this.updateList();
		}
	};

	protected void updateList() {
		if (hostBridge != null) {
			this.portForwards = hostBridge.getPortForwards();
		} else {
			if (this.hostdb == null) return;
			this.portForwards = this.hostdb.getPortForwardsForHost(host);
		}

		mAdapter = new PortForwardAdapter(this, portForwards);
		mListView.setAdapter(mAdapter);
		adjustViewVisibility();
	}

	private class PortForwardViewHolder extends ItemViewHolder {
		public final TextView nickname;
		public final TextView caption;

		public PortForwardBean portForward;

		public PortForwardViewHolder(View v) {
			super(v);

			nickname = (TextView) v.findViewById(android.R.id.text1);
			caption = (TextView) v.findViewById(android.R.id.text2);
		}

		@Override
		public void onClick(View v) {
			if (hostBridge != null) {
				if (portForward.isEnabled())
					hostBridge.disablePortForward(portForward);
				else {
					if (!hostBridge.enablePortForward(portForward))
						Toast.makeText(PortForwardListActivity.this, getString(R.string.portforward_problem), Toast.LENGTH_LONG).show();
				}

				updateHandler.sendEmptyMessage(-1);
			}
		}

		@Override
		public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
			// Create menu to handle deleting and editing port forward
			AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;

			menu.setHeaderTitle(portForward.getNickname());

			MenuItem edit = menu.add(R.string.portforward_edit);
			edit.setOnMenuItemClickListener(new OnMenuItemClickListener() {
				public boolean onMenuItemClick(MenuItem item) {
					final View editTunnelView = inflater.inflate(R.layout.dia_portforward, null, false);

					final Spinner typeSpinner = (Spinner) editTunnelView.findViewById(R.id.portforward_type);
					if (HostDatabase.PORTFORWARD_LOCAL.equals(portForward.getType()))
						typeSpinner.setSelection(0);
					else if (HostDatabase.PORTFORWARD_REMOTE.equals(portForward.getType()))
						typeSpinner.setSelection(1);
					else
						typeSpinner.setSelection(2);

					final EditText nicknameEdit = (EditText) editTunnelView.findViewById(R.id.nickname);
					nicknameEdit.setText(portForward.getNickname());

					final EditText sourcePortEdit = (EditText) editTunnelView.findViewById(R.id.portforward_source);
					sourcePortEdit.setText(String.valueOf(portForward.getSourcePort()));

					final EditText destEdit = (EditText) editTunnelView.findViewById(R.id.portforward_destination);
					if (HostDatabase.PORTFORWARD_DYNAMIC5.equals(portForward.getType())) {
						destEdit.setEnabled(false);
					} else {
						destEdit.setText(String.format("%s:%d", portForward.getDestAddr(), portForward.getDestPort()));
					}

					typeSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
						public void onItemSelected(AdapterView<?> value, View view,
								int position, long id) {
							destEdit.setEnabled(position != 2);
						}

						public void onNothingSelected(AdapterView<?> arg0) {
						}
					});

					new AlertDialog.Builder(PortForwardListActivity.this)
							.setView(editTunnelView)
							.setPositiveButton(R.string.button_change, new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int which) {
									try {
										if (hostBridge != null)
											hostBridge.disablePortForward(portForward);

										portForward.setNickname(nicknameEdit.getText().toString());

										switch (typeSpinner.getSelectedItemPosition()) {
										case 0:
											portForward.setType(HostDatabase.PORTFORWARD_LOCAL);
											break;
										case 1:
											portForward.setType(HostDatabase.PORTFORWARD_REMOTE);
											break;
										case 2:
											portForward.setType(HostDatabase.PORTFORWARD_DYNAMIC5);
											break;
										}

										portForward.setSourcePort(Integer.parseInt(sourcePortEdit.getText().toString()));
										portForward.setDest(destEdit.getText().toString());

										// Use the new settings for the existing connection.
										if (hostBridge != null)
											updateHandler.postDelayed(new Runnable() {
												public void run() {
													hostBridge.enablePortForward(portForward);
													updateHandler.sendEmptyMessage(-1);
												}
											}, LISTENER_CYCLE_TIME);


										if (!hostdb.savePortForward(portForward)) {
											throw new SQLException("Could not save port forward");
										}

										updateHandler.sendEmptyMessage(-1);
									} catch (Exception e) {
										Log.e(TAG, "Could not update port forward", e);
										// TODO Show failure dialog.
									}
								}
							})
							.setNegativeButton(android.R.string.cancel, null).create().show();

					return true;
				}
			});

			MenuItem delete = menu.add(R.string.portforward_delete);
			delete.setOnMenuItemClickListener(new OnMenuItemClickListener() {
				public boolean onMenuItemClick(MenuItem item) {
					// prompt user to make sure they really want this
					new AlertDialog.Builder(PortForwardListActivity.this)
							.setMessage(getString(R.string.delete_message, portForward.getNickname()))
							.setPositiveButton(R.string.delete_pos, new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int which) {
									try {
										// Delete the port forward from the host if needed.
										if (hostBridge != null)
											hostBridge.removePortForward(portForward);

										hostdb.deletePortForward(portForward);
									} catch (Exception e) {
										Log.e(TAG, "Could not delete port forward", e);
									}

									updateHandler.sendEmptyMessage(-1);
								}
							})
							.setNegativeButton(R.string.delete_neg, null).create().show();

					return true;
				}
			});
		}
	}

	@VisibleForTesting
	private class PortForwardAdapter extends ItemAdapter {
		private final List<PortForwardBean> portForwards;

		public PortForwardAdapter(Context context, List<PortForwardBean> portForwards) {
			super(context);
			this.portForwards = portForwards;
		}

		@Override
		public PortForwardViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.item_portforward, parent, false);
			PortForwardViewHolder vh = new PortForwardViewHolder(v);
			return vh;
		}

		@Override
		public void onBindViewHolder(ItemViewHolder holder, int position) {
			PortForwardViewHolder portForwardHolder = (PortForwardViewHolder) holder;
			PortForwardBean portForward = portForwards.get(position);

			portForwardHolder.portForward = portForward;
			portForwardHolder.nickname.setText(portForward.getNickname());
			portForwardHolder.caption.setText(portForward.getDescription());

			if (hostBridge != null && !portForward.isEnabled()) {
				portForwardHolder.nickname.setPaintFlags(portForwardHolder.nickname.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
				portForwardHolder.caption.setPaintFlags(portForwardHolder.caption.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
			}
		}

		@Override
		public long getItemId(int position) {
			return portForwards.get(position).getId();
		}

		@Override
		public int getItemCount() {
			return portForwards.size();
		}
	}
}
